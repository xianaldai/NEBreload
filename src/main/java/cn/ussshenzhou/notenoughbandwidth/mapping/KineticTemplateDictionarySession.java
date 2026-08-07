package cn.ussshenzhou.notenoughbandwidth.mapping;

import cn.ussshenzhou.notenoughbandwidth.NotEnoughBandwidthLegacyConfig;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

// Session for transport direction.
public final class KineticTemplateDictionarySession {

    private static final int MAX_RECENT_SEEDS_PER_LENGTH = 48;
    private static final int MAX_RECENT_SEEDS = 2048;
    private static final int MAX_UNSYNCED_EXACT_SEEDS = 4096;
    private static final int MAX_CANDIDATE_SEEDS_PER_LOOKUP = 24;
    private static final int MIN_TEMPLATE_LITERAL_BYTES = 8;

    private final Map<HashKey, Integer> exactIdByHash = new HashMap<>();
    private final Map<Integer, ExactEntry> exactEntriesById = new HashMap<>();
    private final Map<TemplateKey, Integer> templateIdByKey = new HashMap<>();
    private final Map<Integer, TemplateEntry> templateEntriesById = new HashMap<>();
    private final Map<Integer, List<Integer>> templateIdsByLength = new HashMap<>();
    private final Map<HashKey, UnsyncedExactSeed> unsyncedExactSeeds = new HashMap<>();
    private final Map<Integer, List<SeedEntry>> recentSeedsByLength = new HashMap<>();
    private final List<KineticTemplateMappingCodec.MappingRemoval> pendingRemovals = new ArrayList<>();
    private int nextId;
    private long touchCounter;
    private int totalStoredBytes;
    private int unsyncedExactSeedBytes;
    private int recentSeedCount;
    private int recentSeedBytes;

    void reset() {
        this.exactIdByHash.clear();
        this.exactEntriesById.clear();
        this.templateIdByKey.clear();
        this.templateEntriesById.clear();
        this.templateIdsByLength.clear();
        this.unsyncedExactSeeds.clear();
        this.recentSeedsByLength.clear();
        this.pendingRemovals.clear();
        this.nextId = 0;
        this.touchCounter = 0L;
        this.totalStoredBytes = 0;
        this.unsyncedExactSeedBytes = 0;
        this.recentSeedCount = 0;
        this.recentSeedBytes = 0;
    }

    public byte[] encode(byte[] packetBytes) {
        byte[] safePacketBytes = copyBytes(packetBytes);
        List<KineticTemplateMappingCodec.MappingRemoval> removalsToSend = List.copyOf(this.pendingRemovals);
        this.pendingRemovals.clear();

        List<KineticTemplateMappingCodec.MappingAddition> additions = new ArrayList<>();
        LinkedHashSet<Integer> protectedIds = new LinkedHashSet<>();
        KineticTemplateMappingCodec.MappingEntry entry = selectEntry(safePacketBytes, additions, protectedIds);

        rememberSeed(safePacketBytes);
        evictMappings(protectedIds);

        KineticTemplateMappingCodec.FrameData frameData =
                new KineticTemplateMappingCodec.FrameData(List.copyOf(additions), removalsToSend, entry);
        return KineticTemplateMappingCodec.encodeFrame(frameData);
    }


    public byte[] decode(byte[] encodedBytes) {
        KineticTemplateMappingCodec.FrameData frameData = KineticTemplateMappingCodec.decodeFrame(copyBytes(encodedBytes));
        applyAdditions(frameData.additions());
        applyRemovals(frameData.removals());
        return decodeEntry(frameData.entry(), encodedBytes);
    }

    private KineticTemplateMappingCodec.MappingEntry selectEntry(
            byte[] payload,
            List<KineticTemplateMappingCodec.MappingAddition> additions,
            LinkedHashSet<Integer> protectedIds
    ) {
        int literalEncodedBytes = KineticTemplateMappingCodec.literalEntryEncodedBytes(payload.length);
        if (payload.length == 0 || payload.length > maxPacketBytes()) {
            return KineticTemplateMappingCodec.MappingEntry.literal(payload);
        }

        HashKey payloadKey = new HashKey(payload);
        Integer exactId = this.exactIdByHash.get(payloadKey);
        if (exactId != null
                && KineticTemplateMappingCodec.exactReferenceEntryEncodedBytes(exactId) < literalEncodedBytes) {
            touchExact(exactId);
            protectedIds.add(exactId);
            return KineticTemplateMappingCodec.MappingEntry.exactReference(exactId);
        }

        TemplateMatch existingTemplateMatch = tryMatchExistingTemplate(payload);
        if (existingTemplateMatch != null && existingTemplateMatch.encodedBytes() < literalEncodedBytes) {
            touchTemplate(existingTemplateMatch.mappingId());
            protectedIds.add(existingTemplateMatch.mappingId());
            return KineticTemplateMappingCodec.MappingEntry.templateReference(
                    existingTemplateMatch.mappingId(),
                    existingTemplateMatch.variableBytes()
            );
        }

        UnsyncedExactSeed unsyncedSeed = this.unsyncedExactSeeds.get(payloadKey);
        if (unsyncedSeed != null && Arrays.equals(unsyncedSeed.payload(), payload) && exactAdditionWorthwhile(payload.length)) {
            int mappingId = this.nextId++;
            byte[] storedPayload = copyBytes(payload);
            this.exactIdByHash.put(new HashKey(storedPayload), mappingId);
            this.exactEntriesById.put(mappingId, new ExactEntry(storedPayload, nextTouch()));
            this.totalStoredBytes += storedPayload.length;
            removeUnsyncedExactSeed(payloadKey);
            additions.add(KineticTemplateMappingCodec.MappingAddition.exact(mappingId, storedPayload));
            protectedIds.add(mappingId);
            return KineticTemplateMappingCodec.MappingEntry.exactReference(mappingId);
        }

        TemplateCandidate templateCandidate = tryCreateTemplate(payload);
        if (templateCandidate != null && templateCandidate.referenceEncodedBytes(this.nextId) < literalEncodedBytes) {
            Integer mappingId = this.templateIdByKey.get(templateCandidate.key());
            if (mappingId == null && templateAdditionWorthwhile(templateCandidate, payload.length)) {
                mappingId = this.nextId++;
                TemplateEntry templateEntry = templateCandidate.templateEntry().touch(nextTouch());
                this.templateIdByKey.put(templateCandidate.key(), mappingId);
                this.templateEntriesById.put(mappingId, templateEntry);
                this.templateIdsByLength.computeIfAbsent(templateEntry.totalLength(), ignored -> new ArrayList<>()).add(mappingId);
                this.totalStoredBytes += templateEntry.literalByteCount();
                additions.add(
                        KineticTemplateMappingCodec.MappingAddition.template(
                                mappingId,
                                new KineticTemplateMappingCodec.TemplateDescription(
                                        templateEntry.totalLength(),
                                        templateEntry.segments()
                                )
                        )
                );
            }

            if (mappingId != null) {
                touchTemplate(mappingId);
                protectedIds.add(mappingId);
                return KineticTemplateMappingCodec.MappingEntry.templateReference(mappingId, templateCandidate.variableBytes());
            }
        }

        rememberUnsyncedExactSeed(payloadKey, payload);
        return KineticTemplateMappingCodec.MappingEntry.literal(payload);
    }

    private void applyAdditions(List<KineticTemplateMappingCodec.MappingAddition> additions) {
        for (KineticTemplateMappingCodec.MappingAddition addition : additions) {
            if (addition.kind() == KineticTemplateMappingCodec.ADD_EXACT) {
                byte[] payload = copyBytes(addition.exactPayload());
                this.exactIdByHash.put(new HashKey(payload), addition.mappingId());
                this.exactEntriesById.put(addition.mappingId(), new ExactEntry(payload, nextTouch()));
                this.totalStoredBytes += payload.length;
                this.nextId = Math.max(this.nextId, addition.mappingId() + 1);
                continue;
            }
            if (addition.kind() != KineticTemplateMappingCodec.ADD_TEMPLATE) {
                throw new IllegalArgumentException("Unknown mapping addition kind: " + addition.kind());
            }

            TemplateEntry templateEntry = templateEntryFrom(addition.templateDescription());
            TemplateKey key = TemplateKey.from(templateEntry);
            this.templateIdByKey.put(key, addition.mappingId());
            this.templateEntriesById.put(addition.mappingId(), templateEntry);
            this.templateIdsByLength.computeIfAbsent(templateEntry.totalLength(), ignored -> new ArrayList<>()).add(addition.mappingId());
            this.totalStoredBytes += templateEntry.literalByteCount();
            this.nextId = Math.max(this.nextId, addition.mappingId() + 1);
        }
    }

    private void applyRemovals(List<KineticTemplateMappingCodec.MappingRemoval> removals) {
        for (KineticTemplateMappingCodec.MappingRemoval removal : removals) {
            if (removal.kind() == KineticTemplateMappingCodec.ADD_EXACT) {
                ExactEntry removedEntry = this.exactEntriesById.remove(removal.mappingId());
                if (removedEntry != null) {
                    this.exactIdByHash.remove(new HashKey(removedEntry.payload()));
                    this.totalStoredBytes -= removedEntry.payload().length;
                }
                continue;
            }
            if (removal.kind() != KineticTemplateMappingCodec.ADD_TEMPLATE) {
                throw new IllegalArgumentException("Unknown mapping removal kind: " + removal.kind());
            }

            TemplateEntry removedEntry = this.templateEntriesById.remove(removal.mappingId());
            if (removedEntry == null) {
                continue;
            }
            this.templateIdByKey.remove(TemplateKey.from(removedEntry));
            List<Integer> ids = this.templateIdsByLength.get(removedEntry.totalLength());
            if (ids != null) {
                ids.removeIf(id -> id == removal.mappingId());
                if (ids.isEmpty()) {
                    this.templateIdsByLength.remove(removedEntry.totalLength());
                }
            }
            this.totalStoredBytes -= removedEntry.literalByteCount();
        }
    }

    private byte[] decodeEntry(KineticTemplateMappingCodec.MappingEntry entry, byte[] encodedBytes) {
        if (entry.type() == KineticTemplateMappingCodec.ENTRY_LITERAL) {
            return copyBytes(entry.literalPayload());
        }
        if (entry.type() == KineticTemplateMappingCodec.ENTRY_EXACT_REFERENCE) {
            ExactEntry exactEntry = this.exactEntriesById.get(entry.mappingId());
            if (exactEntry == null) {
                throw new IllegalStateException("Missing exact mapping id " + entry.mappingId());
            }
            this.exactEntriesById.put(entry.mappingId(), exactEntry.touch(nextTouch()));
            return copyBytes(exactEntry.payload());
        }
        if (entry.type() != KineticTemplateMappingCodec.ENTRY_TEMPLATE_REFERENCE) {
            throw new IllegalArgumentException("Unknown mapping entry type: " + entry.type());
        }

        TemplateEntry templateEntry = this.templateEntriesById.get(entry.mappingId());
        if (templateEntry == null) {
            throw new IllegalStateException(
                    "Missing template mapping id " + entry.mappingId()
                            + ", knownTemplateIds=" + this.templateEntriesById.keySet()
                            + ", encodedBytes=" + encodedBytes.length
            );
        }

        this.templateEntriesById.put(entry.mappingId(), templateEntry.touch(nextTouch()));
        return rebuildTemplatePayload(templateEntry, entry.rawVariablePayload());
    }




    private TemplateMatch tryMatchExistingTemplate(byte[] payload) {
        List<Integer> candidateIds = this.templateIdsByLength.get(payload.length);
        if (candidateIds == null || candidateIds.isEmpty()) {
            return null;
        }

        TemplateMatch bestMatch = null;
        for (int mappingId : candidateIds) {
            TemplateEntry templateEntry = this.templateEntriesById.get(mappingId);
            if (templateEntry == null) {
                continue;
            }
            TemplateMatch candidate = matchTemplate(payload, mappingId, templateEntry);
            if (candidate == null) {
                continue;
            }
            if (bestMatch == null || candidate.encodedBytes() < bestMatch.encodedBytes()) {
                bestMatch = candidate;
            }
        }
        return bestMatch;
    }

    private TemplateMatch matchTemplate(byte[] payload, int mappingId, TemplateEntry templateEntry) {
        int payloadIndex = 0;
        List<byte[]> variableBytes = new ArrayList<>();
        for (KineticTemplateMappingCodec.MappingSegment segment : templateEntry.segments()) {
            if (segment.variable()) {
                byte[] bytes = Arrays.copyOfRange(payload, payloadIndex, payloadIndex + segment.length());
                variableBytes.add(bytes);
                payloadIndex += segment.length();
                continue;
            }

            byte[] literalBytes = segment.literalBytes();
            for (int index = 0; index < literalBytes.length; index++) {
                if (payload[payloadIndex + index] != literalBytes[index]) {
                    return null;
                }
            }
            payloadIndex += literalBytes.length;
        }

        return new TemplateMatch(
                mappingId,
                List.copyOf(variableBytes),
                KineticTemplateMappingCodec.templateReferenceEntryEncodedBytes(mappingId, variableBytes)
        );
    }

    private TemplateCandidate tryCreateTemplate(byte[] payload) {
        List<SeedEntry> seeds = this.recentSeedsByLength.get(payload.length);
        if (seeds == null || seeds.isEmpty())
            return null;

        TemplateCandidate bestCandidate = null;
        int considered = 0;
        for (int index = seeds.size() - 1; index >= 0 && considered < MAX_CANDIDATE_SEEDS_PER_LOOKUP; index--, considered++) {
            TemplateCandidate candidate = buildTemplateCandidate(seeds.get(index).payload(), payload);
            if (candidate == null)
                continue;

            if (bestCandidate == null || candidate.referenceEncodedBytes(this.nextId) < bestCandidate.referenceEncodedBytes(this.nextId))
                bestCandidate = candidate;
        }
        return bestCandidate;
    }

    private TemplateCandidate buildTemplateCandidate(byte[] base, byte[] payload) {
        if (base.length != payload.length || base.length == 0) {
            return null;
        }

        List<Range> diffRuns = new ArrayList<>();
        int totalChangedBytes = 0;
        int index = 0;
        while (index < base.length) {
            if (base[index] == payload[index]) {
                index++;
                continue;
            }

            int start = index;
            while (index < base.length && base[index] != payload[index]) {
                index++;
            }

            int endExclusive = index;
            diffRuns.add(new Range(start, endExclusive - start));
            totalChangedBytes += endExclusive - start;
            if (diffRuns.size() > maxDiffRuns() || totalChangedBytes > maxChangedBytes()) {
                return null;
            }
        }

        if (diffRuns.isEmpty() || base.length - totalChangedBytes < MIN_TEMPLATE_LITERAL_BYTES) {
            return null;
        }

        List<KineticTemplateMappingCodec.MappingSegment> segments = new ArrayList<>();
        List<byte[]> variableBytes = new ArrayList<>();
        int position = 0;
        int literalByteCount = 0;

        for (Range diffRun : diffRuns) {
            if (diffRun.start() > position) {
                byte[] literalBytes = Arrays.copyOfRange(base, position, diffRun.start());
                literalByteCount += literalBytes.length;
                segments.add(KineticTemplateMappingCodec.MappingSegment.literal(literalBytes));
            }

            byte[] variable = Arrays.copyOfRange(payload, diffRun.start(), diffRun.endExclusive());
            variableBytes.add(variable);
            segments.add(KineticTemplateMappingCodec.MappingSegment.variable(variable.length));
            position = diffRun.endExclusive();
        }

        if (position < base.length) {
            byte[] literalBytes = Arrays.copyOfRange(base, position, base.length);
            literalByteCount += literalBytes.length;
            segments.add(KineticTemplateMappingCodec.MappingSegment.literal(literalBytes));
        }

        TemplateEntry templateEntry = new TemplateEntry(base.length, List.copyOf(segments), literalByteCount, 0L);
        TemplateKey templateKey = TemplateKey.from(templateEntry);
        return new TemplateCandidate(templateEntry, templateKey, List.copyOf(variableBytes));
    }

    private void evictMappings(LinkedHashSet<Integer> protectedIds) {
        if (this.exactEntriesById.size() + this.templateEntriesById.size() <= maxEntries()
                && this.totalStoredBytes <= maxPayloadBytes())
            return;

        List<EvictionCandidate> candidates = new ArrayList<>();
        for (Map.Entry<Integer, ExactEntry> entry : this.exactEntriesById.entrySet()) {
            if (!protectedIds.contains(entry.getKey())) {
                candidates.add(new EvictionCandidate(KineticTemplateMappingCodec.ADD_EXACT, entry.getKey(), entry.getValue().lastTouched()));
            }
        }
        for (Map.Entry<Integer, TemplateEntry> entry : this.templateEntriesById.entrySet()) {
            if (!protectedIds.contains(entry.getKey())) {
                candidates.add(new EvictionCandidate(KineticTemplateMappingCodec.ADD_TEMPLATE, entry.getKey(), entry.getValue().lastTouched()));
            }
        }
        candidates.sort(Comparator.comparingLong(EvictionCandidate::lastTouched));

        for (EvictionCandidate candidate : candidates) {
            if (this.exactEntriesById.size() + this.templateEntriesById.size() <= maxEntries()
                    && this.totalStoredBytes <= maxPayloadBytes()) {
                break;
            }

            if (candidate.kind() == KineticTemplateMappingCodec.ADD_EXACT) {
                ExactEntry removedEntry = this.exactEntriesById.remove(candidate.mappingId());
                if (removedEntry == null) {
                    continue;
                }
                this.exactIdByHash.remove(new HashKey(removedEntry.payload()));
                this.totalStoredBytes -= removedEntry.payload().length;
                this.pendingRemovals.add(new KineticTemplateMappingCodec.MappingRemoval(KineticTemplateMappingCodec.ADD_EXACT, candidate.mappingId()));
                continue;
            }

            TemplateEntry removedEntry = this.templateEntriesById.remove(candidate.mappingId());
            if (removedEntry == null) {
                continue;
            }
            this.templateIdByKey.remove(TemplateKey.from(removedEntry));
            List<Integer> ids = this.templateIdsByLength.get(removedEntry.totalLength());
            if (ids != null) {
                ids.removeIf(id -> id == candidate.mappingId());
                if (ids.isEmpty()) {
                    this.templateIdsByLength.remove(removedEntry.totalLength());
                }
            }
            this.totalStoredBytes -= removedEntry.literalByteCount();
            this.pendingRemovals.add(new KineticTemplateMappingCodec.MappingRemoval(KineticTemplateMappingCodec.ADD_TEMPLATE, candidate.mappingId()));
        }
    }



    private static byte[] rebuildTemplatePayload(TemplateEntry templateEntry, byte[] rawVariablePayload) {
        byte[] payload = new byte[templateEntry.totalLength()];
        int writeIndex = 0;
        int variablePayloadIndex = 0;

        for (KineticTemplateMappingCodec.MappingSegment segment : templateEntry.segments()) {
            if (segment.variable()) {
                int variableLength = segment.length();
                if (rawVariablePayload == null || variablePayloadIndex + variableLength > rawVariablePayload.length) {
                    throw new IllegalStateException(
                            "Template variable payload is shorter than expected. expectedNextBytes=" + variableLength
                    );
                }
                System.arraycopy(rawVariablePayload, variablePayloadIndex, payload, writeIndex, variableLength);
                variablePayloadIndex += variableLength;
                writeIndex += variableLength;
                continue;
            }

            byte[] literalBytes = segment.literalBytes();
            System.arraycopy(literalBytes, 0, payload, writeIndex, literalBytes.length);
            writeIndex += literalBytes.length;
        }

        if (rawVariablePayload != null && variablePayloadIndex != rawVariablePayload.length)
            throw new IllegalStateException(
                    "Template variable payload left trailing bytes: " + (rawVariablePayload.length - variablePayloadIndex));
        return payload;
    }

    private TemplateEntry templateEntryFrom(KineticTemplateMappingCodec.TemplateDescription templateDescription) {
        int literalByteCount = templateDescription.segments().stream()
                .filter(segment -> !segment.variable())
                .mapToInt(KineticTemplateMappingCodec.MappingSegment::length)
                .sum();
        return new TemplateEntry(
                templateDescription.totalLength(),
                templateDescription.segments(),
                literalByteCount,
                nextTouch()
        );
    }

    private boolean exactAdditionWorthwhile(int payloadLength) {
        int mappingId = this.nextId;
        int literalBytes = KineticTemplateMappingCodec.literalEntryEncodedBytes(payloadLength);
        int referenceBytes = KineticTemplateMappingCodec.exactReferenceEntryEncodedBytes(mappingId);
        int additionBytes = KineticTemplateMappingCodec.exactAdditionEncodedBytes(mappingId, payloadLength);
        return additionBytes + (referenceBytes * 2) < literalBytes * 2;
    }

    private boolean templateAdditionWorthwhile(TemplateCandidate templateCandidate, int payloadLength) {
        int mappingId = this.nextId;
        int literalBytes = KineticTemplateMappingCodec.literalEntryEncodedBytes(payloadLength);
        int referenceBytes = templateCandidate.referenceEncodedBytes(mappingId);
        int additionBytes = KineticTemplateMappingCodec.templateAdditionEncodedBytes(
                mappingId,
                templateCandidate.templateEntry().totalLength(),
                templateCandidate.templateEntry().segments()
        );
        return additionBytes + (referenceBytes * 2) < literalBytes * 2;
    }



    private record ExactEntry(byte[] payload, long lastTouched) {
        private ExactEntry touch(long touch) {
            return new ExactEntry(this.payload, touch);
        }
    }

    private record UnsyncedExactSeed(byte[] payload, long lastTouched) {
    }

    private record SeedEntry(byte[] payload, long lastTouched) {
    }

    private record TemplateEntry(
            int totalLength,
            List<KineticTemplateMappingCodec.MappingSegment> segments,
            int literalByteCount,
            long lastTouched
    ) {
        private TemplateEntry touch(long touch) {
            return new TemplateEntry(this.totalLength, this.segments, this.literalByteCount, touch);
        }
    }

    private record TemplateCandidate(TemplateEntry templateEntry, TemplateKey key, List<byte[]> variableBytes) {
        private int referenceEncodedBytes(int mappingId) {
            return KineticTemplateMappingCodec.templateReferenceEntryEncodedBytes(Math.max(mappingId, 0), this.variableBytes);
        }
    }

    private record TemplateMatch(int mappingId, List<byte[]> variableBytes, int encodedBytes) {
    }

    private record EvictionCandidate(int kind, int mappingId, long lastTouched) {
    }

    private record Range(int start, int length) {
        private int endExclusive() {
            return this.start + this.length;
        }
    }

    private record HashKey(byte[] bytes) {
        private HashKey(byte[] bytes) {
            this.bytes = Arrays.copyOf(bytes, bytes.length);
        }

        @Override
        public boolean equals(Object object) {
            return object instanceof HashKey other && Arrays.equals(this.bytes, other.bytes);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(this.bytes);
        }
    }

    private record TemplateKey(byte[] bytes) {
        private static TemplateKey from(TemplateEntry templateEntry) {
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                buffer.writeVarInt(templateEntry.totalLength());
                buffer.writeVarInt(templateEntry.segments().size());
                for (KineticTemplateMappingCodec.MappingSegment segment : templateEntry.segments()) {
                    buffer.writeBoolean(segment.variable());
                    buffer.writeVarInt(segment.length());
                    if (!segment.variable()) {
                        buffer.writeBytes(segment.literalBytes());
                    }
                }

                byte[] bytes = new byte[buffer.readableBytes()];
                buffer.getBytes(0, bytes);
                return new TemplateKey(bytes);
            } finally {
                buffer.release();
            }
        }

        @Override
        public boolean equals(Object object) {
            return object instanceof TemplateKey other && Arrays.equals(this.bytes, other.bytes);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(this.bytes);
        }
    }




    //Tools

    private long nextTouch() {
        return ++this.touchCounter;
    }

    private static int maxPacketBytes() {
        var cfg = NotEnoughBandwidthLegacyConfig.get();
        return cfg != null && cfg.packetDictionaryMaxPacketBytes > 0 ? cfg.packetDictionaryMaxPacketBytes : 4096;
    }

    private static int maxEntries() {
        var cfg = NotEnoughBandwidthLegacyConfig.get();
        return cfg != null && cfg.packetDictionaryMaxEntries > 0 ? cfg.packetDictionaryMaxEntries : 8192;
    }

    private static int maxPayloadBytes() {
        var cfg = NotEnoughBandwidthLegacyConfig.get();
        return cfg != null && cfg.packetDictionaryMaxPayloadBytes > 0 ? cfg.packetDictionaryMaxPayloadBytes : 2097152;
    }

    private static int maxDiffRuns() {
        var cfg = NotEnoughBandwidthLegacyConfig.get();
        return cfg != null && cfg.packetDictionaryMaxDiffRuns > 0 ? cfg.packetDictionaryMaxDiffRuns : 8;
    }

    private static int maxChangedBytes() {
        var cfg = NotEnoughBandwidthLegacyConfig.get();
        return cfg != null && cfg.packetDictionaryMaxChangedBytes > 0 ? cfg.packetDictionaryMaxChangedBytes : 128;
    }

    private static byte[] copyBytes(byte[] sourceBytes) {
        return sourceBytes == null ? new byte[0] : Arrays.copyOf(sourceBytes, sourceBytes.length);
    }

    private void touchExact(int mappingId) {
        ExactEntry entry = this.exactEntriesById.get(mappingId);
        if (entry != null)
            this.exactEntriesById.put(mappingId, entry.touch(nextTouch()));
    }
    private void touchTemplate(int mappingId) {
        TemplateEntry entry = this.templateEntriesById.get(mappingId);
        if (entry != null)
            this.templateEntriesById.put(mappingId, entry.touch(nextTouch()));
    }

    private void rememberSeed(byte[] payload) {
        if (!isSeedPayloadEligible(payload)) {
            return;
        }

        byte[] storedPayload = copyBytes(payload);
        SeedEntry seedEntry = new SeedEntry(storedPayload, nextTouch());
        List<SeedEntry> seeds = this.recentSeedsByLength.computeIfAbsent(payload.length, ignored -> new ArrayList<>());
        seeds.add(seedEntry);
        this.recentSeedCount++;
        this.recentSeedBytes += storedPayload.length;
        if (seeds.size() > MAX_RECENT_SEEDS_PER_LENGTH) {
            removeRecentSeedAt(payload.length, seeds, 0);
        }
        trimRecentSeeds();
    }

    private void rememberUnsyncedExactSeed(HashKey payloadKey, byte[] payload) {
        if (!isSeedPayloadEligible(payload)) {
            return;
        }

        byte[] storedPayload = copyBytes(payload);
        UnsyncedExactSeed previousSeed = this.unsyncedExactSeeds.put(
                payloadKey,
                new UnsyncedExactSeed(storedPayload, nextTouch())
        );
        if (previousSeed != null) {
            this.unsyncedExactSeedBytes -= previousSeed.payload().length;
        }
        this.unsyncedExactSeedBytes += storedPayload.length;
        trimUnsyncedExactSeeds();
    }

    private void removeUnsyncedExactSeed(HashKey payloadKey) {
        UnsyncedExactSeed removedSeed = this.unsyncedExactSeeds.remove(payloadKey);
        if (removedSeed != null) {
            this.unsyncedExactSeedBytes -= removedSeed.payload().length;
        }
    }

    private void trimUnsyncedExactSeeds() {
        while (this.unsyncedExactSeeds.size() > maxUnsyncedExactSeeds()
                || this.unsyncedExactSeedBytes > maxUnsyncedExactSeedBytes()) {
            HashKey oldestKey = null;
            long oldestTouch = Long.MAX_VALUE;
            for (Map.Entry<HashKey, UnsyncedExactSeed> entry : this.unsyncedExactSeeds.entrySet()) {
                if (entry.getValue().lastTouched() < oldestTouch) {
                    oldestTouch = entry.getValue().lastTouched();
                    oldestKey = entry.getKey();
                }
            }
            if (oldestKey == null) {
                break;
            }
            removeUnsyncedExactSeed(oldestKey);
        }
    }

    private void trimRecentSeeds() {
        while (this.recentSeedCount > maxRecentSeeds() || this.recentSeedBytes > maxRecentSeedBytes()) {
            Integer oldestLength = null;
            int oldestIndex = -1;
            long oldestTouch = Long.MAX_VALUE;
            for (Map.Entry<Integer, List<SeedEntry>> entry : this.recentSeedsByLength.entrySet()) {
                List<SeedEntry> seeds = entry.getValue();
                for (int index = 0; index < seeds.size(); index++) {
                    SeedEntry seed = seeds.get(index);
                    if (seed.lastTouched() < oldestTouch) {
                        oldestTouch = seed.lastTouched();
                        oldestLength = entry.getKey();
                        oldestIndex = index;
                    }
                }
            }
            if (oldestLength == null) {
                break;
            }
            List<SeedEntry> seeds = this.recentSeedsByLength.get(oldestLength);
            if (seeds == null || oldestIndex < 0 || oldestIndex >= seeds.size()) {
                break;
            }
            removeRecentSeedAt(oldestLength, seeds, oldestIndex);
        }
    }

    private void removeRecentSeedAt(int payloadLength, List<SeedEntry> seeds, int index) {
        SeedEntry removedSeed = seeds.remove(index);
        this.recentSeedCount--;
        this.recentSeedBytes -= removedSeed.payload().length;
        if (seeds.isEmpty()) {
            this.recentSeedsByLength.remove(payloadLength);
        }
    }

    private static boolean isSeedPayloadEligible(byte[] payload) {
        return payload != null && payload.length > 0 && payload.length <= maxPacketBytes();
    }

    private static int maxRecentSeeds() {
        return Math.min(Math.max(maxEntries(), 1), MAX_RECENT_SEEDS);
    }

    private static int maxRecentSeedBytes() {
        return Math.max(Math.min(maxPayloadBytes(), 2097152), 1);
    }

    private static int maxUnsyncedExactSeeds() {
        return Math.min(Math.max(maxEntries(), 1), MAX_UNSYNCED_EXACT_SEEDS);
    }

    private static int maxUnsyncedExactSeedBytes() {
        return Math.max(Math.min(maxPayloadBytes(), 2097152), 1);
    }


}
