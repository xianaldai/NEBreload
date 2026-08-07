package cn.ussshenzhou.notenoughbandwidth.mapping;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;


final class KineticTemplateMappingCodec {

    static final int FRAME_VERSION = 1;
    static final int ADD_EXACT = 0;
    static final int ADD_TEMPLATE = 1;
    static final int ENTRY_LITERAL = 0;
    static final int ENTRY_EXACT_REFERENCE = 1;
    static final int ENTRY_TEMPLATE_REFERENCE = 2;
    private static final int MAX_ADDITION_COUNT = 8192;
    private static final int MAX_REMOVAL_COUNT = 8192;
    private static final int MAX_TEMPLATE_SEGMENT_COUNT = 4096;
    private static final int MAX_MAPPING_PAYLOAD_BYTES = 16 * 1024 * 1024;

    private KineticTemplateMappingCodec() {
    }

    // Mapping frame encoding:
    static byte[] encodeFrame(FrameData frameData) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(FRAME_VERSION);
            buffer.writeVarInt(frameData.additions().size());
            for (MappingAddition addition : frameData.additions()) {
                writeAddition(buffer, addition);
            }

            buffer.writeVarInt(frameData.removals().size());
            for (MappingRemoval removal : frameData.removals()) {
                buffer.writeVarInt(removal.kind());
                buffer.writeVarInt(removal.mappingId());
            }

            writeEntry(buffer, frameData.entry());
            return copyReadableBytes(buffer);
        } finally {
            buffer.release();
        }
    }

    // Mapping frame decoding:
    static FrameData decodeFrame(byte[] encodedBytes) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(encodedBytes));
        try {
            int frameVersion = buffer.readVarInt();
            if (frameVersion != FRAME_VERSION) {
                throw new IllegalStateException("Unsupported mapping frame version: " + frameVersion);
            }

            int additionCount = readBoundedVarInt(buffer, MAX_ADDITION_COUNT, "mapping addition count");
            List<MappingAddition> additions = new ArrayList<>(additionCount);
            for (int index = 0; index < additionCount; index++) {
                additions.add(readAddition(buffer));
            }

            int removalCount = readBoundedVarInt(buffer, MAX_REMOVAL_COUNT, "mapping removal count");
            List<MappingRemoval> removals = new ArrayList<>(removalCount);
            for (int index = 0; index < removalCount; index++) {
                removals.add(new MappingRemoval(buffer.readVarInt(), buffer.readVarInt()));
            }

            MappingEntry entry = readEntry(buffer);
            if (buffer.isReadable()) {
                throw new IllegalStateException("Mapping frame left trailing bytes: " + buffer.readableBytes());
            }
            return new FrameData(List.copyOf(additions), List.copyOf(removals), entry);
        } finally {
            buffer.release();
        }
    }


    static int templateAdditionEncodedBytes(int mappingId, int totalLength, List<MappingSegment> segments) {
        int encodedBytes = varIntSize(ADD_TEMPLATE) + varIntSize(mappingId) + varIntSize(totalLength) + varIntSize(segments.size());
        for (MappingSegment segment : segments) {
            encodedBytes += 1;
            encodedBytes += varIntSize(segment.length());
            if (!segment.variable()) {
                encodedBytes += segment.literalBytes().length;
            }
        }
        return encodedBytes;
    }


    private static void writeAddition(FriendlyByteBuf buffer, MappingAddition addition) {
        buffer.writeVarInt(addition.kind());
        buffer.writeVarInt(addition.mappingId());
        if (addition.kind() == ADD_EXACT) {
            byte[] payload = addition.exactPayload();
            buffer.writeVarInt(payload.length);
            buffer.writeBytes(payload);
            return;
        }
        if (addition.kind() != ADD_TEMPLATE)
            throw new IllegalArgumentException("Unknown mapping addition kind: " + addition.kind());

        TemplateDescription description = addition.templateDescription();
        buffer.writeVarInt(description.totalLength());
        buffer.writeVarInt(description.segments().size());
        for (MappingSegment segment : description.segments()) {
            buffer.writeBoolean(segment.variable());
            buffer.writeVarInt(segment.length());
            if (!segment.variable())
                buffer.writeBytes(segment.literalBytes());
        }
    }

    private static MappingAddition readAddition(FriendlyByteBuf buffer) {
        int kind = buffer.readVarInt();
        int mappingId = buffer.readVarInt();
        if (kind == ADD_EXACT) {
            int payloadLength = readBoundedReadableLength(buffer, MAX_MAPPING_PAYLOAD_BYTES, "exact mapping payload bytes");
            byte[] payload = new byte[payloadLength];
            buffer.readBytes(payload);
            return MappingAddition.exact(mappingId, payload);
        }
        if (kind != ADD_TEMPLATE)
            throw new IllegalArgumentException("Unknown mapping addition kind: " + kind);

        int totalLength = readBoundedVarInt(buffer, MAX_MAPPING_PAYLOAD_BYTES, "template total bytes");
        int segmentCount = readBoundedVarInt(buffer, MAX_TEMPLATE_SEGMENT_COUNT, "template segment count");
        List<MappingSegment> segments = new ArrayList<>(segmentCount);
        for (int index = 0; index < segmentCount; index++) {
            boolean variable = buffer.readBoolean();
            int length = readBoundedVarInt(buffer, MAX_MAPPING_PAYLOAD_BYTES, "template segment bytes");
            if (variable) {
                segments.add(MappingSegment.variable(length));
                continue;
            }
            if (length > buffer.readableBytes()) {
                throw new IllegalStateException("template literal segment exceeds remaining bytes: " + length + " > " + buffer.readableBytes());
            }
            byte[] literalBytes = new byte[length];
            buffer.readBytes(literalBytes);
            segments.add(MappingSegment.literal(literalBytes));
        }
        return MappingAddition.template(mappingId, new TemplateDescription(totalLength, List.copyOf(segments)));
    }

    private static void writeEntry(FriendlyByteBuf buffer, MappingEntry entry) {
        buffer.writeVarInt(entry.type());
        switch (entry.type()) {
            case ENTRY_LITERAL -> {
                byte[] payload = entry.literalPayload();
                buffer.writeVarInt(payload.length);
                buffer.writeBytes(payload);
            }
            case ENTRY_EXACT_REFERENCE -> buffer.writeVarInt(entry.mappingId());
            case ENTRY_TEMPLATE_REFERENCE -> {
                buffer.writeVarInt(entry.mappingId());
                for (byte[] variableBytes : entry.variableBytes()) {
                    buffer.writeBytes(variableBytes);
                }
            }
            default -> throw new IllegalArgumentException("Unknown mapping entry type: " + entry.type());
        }
    }

    private static MappingEntry readEntry(FriendlyByteBuf buffer) {
        int entryType = buffer.readVarInt();
        return switch (entryType) {
            case ENTRY_LITERAL -> {
                int payloadLength = readBoundedReadableLength(buffer, MAX_MAPPING_PAYLOAD_BYTES, "literal mapping payload bytes");
                byte[] payload = new byte[payloadLength];
                buffer.readBytes(payload);
                yield MappingEntry.literal(payload);
            }
            case ENTRY_EXACT_REFERENCE -> MappingEntry.exactReference(buffer.readVarInt());
            case ENTRY_TEMPLATE_REFERENCE -> {
                int mappingId = buffer.readVarInt();
                if (buffer.readableBytes() > MAX_MAPPING_PAYLOAD_BYTES) {
                    throw new IllegalStateException("template reference payload out of range: " + buffer.readableBytes());
                }
                byte[] rawVariablePayload = new byte[buffer.readableBytes()];
                buffer.readBytes(rawVariablePayload);
                yield MappingEntry.encodedTemplateReference(mappingId, rawVariablePayload);
            }
            default -> throw new IllegalArgumentException("Unknown mapping entry type: " + entryType);
        };
    }


    private static int readBoundedVarInt(FriendlyByteBuf buffer, int maxValue, String fieldName) {
        int value = buffer.readVarInt();
        if (value < 0 || value > maxValue) {
            throw new IllegalStateException(fieldName + " out of range: " + value);
        }
        return value;
    }


    private static int readBoundedReadableLength(FriendlyByteBuf buffer, int maxValue, String fieldName) {
        int value = readBoundedVarInt(buffer, maxValue, fieldName);
        if (value > buffer.readableBytes()) {
            throw new IllegalStateException(fieldName + " exceeds remaining bytes: " + value + " > " + buffer.readableBytes());
        }
        return value;
    }

    //tool ---

    static int literalEntryEncodedBytes(int payloadLength) {
        return varIntSize(ENTRY_LITERAL) + varIntSize(payloadLength) + payloadLength;
    }

    static int exactReferenceEntryEncodedBytes(int mappingId) {
        return varIntSize(ENTRY_EXACT_REFERENCE) + varIntSize(mappingId);
    }

    static int templateReferenceEntryEncodedBytes(int mappingId, List<byte[]> variableBytes) {
        int variablePayloadBytes = variableBytes.stream().mapToInt(bytes -> bytes.length).sum();
        return varIntSize(ENTRY_TEMPLATE_REFERENCE) + varIntSize(mappingId) + variablePayloadBytes;
    }

    static int exactAdditionEncodedBytes(int mappingId, int payloadLength) {
        return varIntSize(ADD_EXACT) + varIntSize(mappingId) + varIntSize(payloadLength) + payloadLength;
    }

    static int varIntSize(int value) {
        int safeValue = Math.max(value, 0);
        int size = 1;
        while ((safeValue & ~0x7F) != 0) {
            safeValue >>>= 7;
            size++;}
        return size;
    }

    private static byte[] copyReadableBytes(FriendlyByteBuf buffer) {
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.getBytes(0, bytes);
        return bytes;
    }

    record FrameData(List<MappingAddition> additions, List<MappingRemoval> removals, MappingEntry entry) {
    }

    record MappingAddition(int kind, int mappingId, byte[] exactPayload, TemplateDescription templateDescription) {
        static MappingAddition exact(int mappingId, byte[] payload) {
            return new MappingAddition(ADD_EXACT, mappingId, payload, null);
        }

        static MappingAddition template(int mappingId, TemplateDescription templateDescription) {
            return new MappingAddition(ADD_TEMPLATE, mappingId, null, templateDescription);
        }
    }

    record MappingRemoval(int kind, int mappingId) { }

    record MappingEntry(int type, int mappingId, byte[] literalPayload, byte[] rawVariablePayload, List<byte[]> variableBytes) {
        static MappingEntry literal(byte[] payload) {
            return new MappingEntry(ENTRY_LITERAL, -1, payload, null, List.of());
        }

        static MappingEntry exactReference(int mappingId) {
            return new MappingEntry(ENTRY_EXACT_REFERENCE, mappingId, null, null, List.of());
        }

        static MappingEntry templateReference(int mappingId, List<byte[]> variableBytes) {
            return new MappingEntry(ENTRY_TEMPLATE_REFERENCE, mappingId, null, null, List.copyOf(variableBytes));
        }

        static MappingEntry encodedTemplateReference(int mappingId, byte[] rawVariablePayload) {
            return new MappingEntry(ENTRY_TEMPLATE_REFERENCE, mappingId, null, rawVariablePayload, List.of());
        }
    }

    record TemplateDescription(int totalLength, List<MappingSegment> segments) {
    }

    record MappingSegment(boolean variable, int length, byte[] literalBytes) {
        static MappingSegment variable(int length) {
            return new MappingSegment(true, length, null);
        }

        static MappingSegment literal(byte[] literalBytes) {
            return new MappingSegment(false, literalBytes.length, literalBytes);
        }
    }
}
