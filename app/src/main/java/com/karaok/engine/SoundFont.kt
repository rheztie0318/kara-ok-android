package com.karaok.engine

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.nio.channels.FileChannel

class SoundFontError(msg: String) : Exception(msg)

/** SoundFont 2 (.sf2) reader. Samples stay memory-mapped, so even big SoundFonts don't fill the heap. */
class SoundFont(file: File) {
    companion object {
        const val START = 0; const val END = 1; const val STARTLOOP = 2; const val ENDLOOP = 3
        const val START_COARSE = 4; const val FILTER_FC = 8; const val FILTER_Q = 9; const val END_COARSE = 12
        const val PAN = 17; const val DELAY = 33; const val ATTACK = 34; const val HOLD = 35
        const val DECAY = 36; const val SUSTAIN = 37; const val RELEASE = 38; const val INSTRUMENT = 41
        const val KEYRANGE = 43; const val VELRANGE = 44; const val STARTLOOP_COARSE = 45; const val KEYNUM = 46
        const val VELOCITY = 47; const val ATTEN = 48; const val ENDLOOP_COARSE = 50; const val COARSE = 51
        const val FINE = 52; const val SAMPLE = 53; const val MODES = 54; const val SCALE = 56; const val ROOT = 58
        const val NGEN = 61

        /** Generators that are not added at preset level. */
        private val NOT_ADDITIVE = setOf(
            INSTRUMENT, KEYRANGE, VELRANGE, SAMPLE, MODES, ROOT, KEYNUM, VELOCITY, START, END,
            STARTLOOP, ENDLOOP, START_COARSE, END_COARSE, STARTLOOP_COARSE, ENDLOOP_COARSE,
        )
        private val UNSIGNED = setOf(INSTRUMENT, SAMPLE, MODES)

        fun defaults(): IntArray {
            val g = IntArray(NGEN)
            g[FILTER_FC] = 13500; g[DELAY] = -12000; g[ATTACK] = -12000; g[HOLD] = -12000
            g[DECAY] = -12000; g[RELEASE] = -12000; g[SCALE] = 100; g[ROOT] = -1
            g[KEYNUM] = -1; g[VELOCITY] = -1
            return g
        }
    }

    class Zone(val gens: Map<Int, Int>) {
        val loKey: Int; val hiKey: Int; val loVel: Int; val hiVel: Int
        init {
            val kr = gens[KEYRANGE] ?: 0x7F00
            val vr = gens[VELRANGE] ?: 0x7F00
            loKey = kr and 0xFF; hiKey = (kr shr 8) and 0xFF
            loVel = vr and 0xFF; hiVel = (vr shr 8) and 0xFF
        }
        fun matches(key: Int, vel: Int) = key in loKey..hiKey && vel in loVel..hiVel
    }

    class Header(val start: Int, val end: Int, val loopStart: Int, val loopEnd: Int, val rate: Int, val pitch: Int, val corr: Int)

    private class Group(val global: Map<Int, Int>, val zones: List<Zone>)

    val samples: ShortBuffer
    val sampleCount: Int
    val headers = ArrayList<Header>()
    private val instruments = ArrayList<Group>()
    private val presets = HashMap<Int, Group>()          // bank*128+program
    private val cache = java.util.concurrent.ConcurrentHashMap<Long, List<IntArray>>()

    init {
        val raf = RandomAccessFile(file, "r")
        val ch = raf.channel
        val map = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()).order(ByteOrder.LITTLE_ENDIAN)
        raf.close()
        if (map.capacity() < 12 || tag(map, 0) != "RIFF" || tag(map, 8) != "sfbk") throw SoundFontError("Not a SoundFont (.sf2) file.")
        val chunks = HashMap<String, Pair<Int, Int>>()
        walk(map, 12, map.capacity(), chunks)
        val smpl = chunks["smpl"] ?: throw SoundFontError("This SoundFont has no samples.")
        if (chunks["phdr"] == null || chunks["shdr"] == null) throw SoundFontError("This SoundFont has no presets.")
        val sb = map.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        sb.position(smpl.first)
        sb.limit(smpl.first + smpl.second / 2 * 2)
        samples = sb.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        sampleCount = samples.capacity()
        readHeaders(map, chunks["shdr"]!!)
        readInstruments(map, chunks["inst"]!!, chunks["ibag"]!!, chunks["igen"]!!)
        readPresets(map, chunks["phdr"]!!, chunks["pbag"]!!, chunks["pgen"]!!)
    }

    private fun tag(b: ByteBuffer, at: Int): String {
        val a = ByteArray(4)
        for (i in 0 until 4) a[i] = b.get(at + i)
        return String(a, Charsets.ISO_8859_1)
    }

    private fun walk(b: ByteBuffer, from: Int, end: Int, out: HashMap<String, Pair<Int, Int>>) {
        var i = from
        while (i + 8 <= end) {
            val id = tag(b, i)
            val size = b.getInt(i + 4)
            val body = i + 8
            if (size < 0 || body + size > b.capacity()) break
            if (id == "LIST") walk(b, body + 4, body + size, out) else out[id] = body to size
            i = body + size + (size and 1)
        }
    }

    private fun u16(b: ByteBuffer, at: Int) = b.getShort(at).toInt() and 0xFFFF

    private fun gens(b: ByteBuffer, gen: Pair<Int, Int>, from: Int, to: Int): Map<Int, Int> {
        val m = HashMap<Int, Int>()
        for (k in from until to) {
            val at = gen.first + k * 4
            if (at + 4 > gen.first + gen.second) break
            val oper = u16(b, at)
            val v = when (oper) {
                KEYRANGE, VELRANGE -> u16(b, at + 2)               // lo in low byte, hi in high byte
                in UNSIGNED -> u16(b, at + 2)
                else -> b.getShort(at + 2).toInt()
            }
            m[oper] = v
        }
        return m
    }

    private fun bags(b: ByteBuffer, bag: Pair<Int, Int>): IntArray = IntArray(bag.second / 4) { u16(b, bag.first + it * 4) }

    private fun readHeaders(b: ByteBuffer, shdr: Pair<Int, Int>) {
        for (k in 0 until shdr.second / 46) {
            val at = shdr.first + k * 46 + 20
            headers.add(
                Header(
                    b.getInt(at), b.getInt(at + 4), b.getInt(at + 8), b.getInt(at + 12),
                    b.getInt(at + 16).let { if (it <= 0) 44100 else it },
                    b.get(at + 20).toInt() and 0xFF, b.get(at + 21).toInt(),
                )
            )
        }
    }

    private fun readInstruments(b: ByteBuffer, inst: Pair<Int, Int>, ibag: Pair<Int, Int>, igen: Pair<Int, Int>) {
        val n = inst.second / 22
        val bagIdx = IntArray(n) { u16(b, inst.first + it * 22 + 20) }
        val bags = bags(b, ibag)
        for (k in 0 until n - 1) {
            var glob: Map<Int, Int> = emptyMap()
            val zones = ArrayList<Zone>()
            for (bg in bagIdx[k] until bagIdx[k + 1]) {
                if (bg + 1 >= bags.size) break
                val g = gens(b, igen, bags[bg], bags[bg + 1])
                if (SAMPLE in g) zones.add(Zone(g)) else if (bg == bagIdx[k]) glob = g
            }
            instruments.add(Group(glob, zones))
        }
    }

    private fun readPresets(b: ByteBuffer, phdr: Pair<Int, Int>, pbag: Pair<Int, Int>, pgen: Pair<Int, Int>) {
        val n = phdr.second / 38
        val prog = IntArray(n) { u16(b, phdr.first + it * 38 + 20) }
        val bank = IntArray(n) { u16(b, phdr.first + it * 38 + 22) }
        val bag = IntArray(n) { u16(b, phdr.first + it * 38 + 24) }
        val bags = bags(b, pbag)
        for (k in 0 until n - 1) {
            var glob: Map<Int, Int> = emptyMap()
            val zones = ArrayList<Zone>()
            for (bg in bag[k] until bag[k + 1]) {
                if (bg + 1 >= bags.size) break
                val g = gens(b, pgen, bags[bg], bags[bg + 1])
                if (INSTRUMENT in g) zones.add(Zone(g)) else if (bg == bag[k]) glob = g
            }
            presets[bank[k] * 128 + prog[k]] = Group(glob, zones)
        }
    }

    private fun findPreset(bank: Int, program: Int): Group? =
        presets[bank * 128 + program]
            ?: (if (bank == 128) presets[128 * 128] else null)
            ?: presets[program] ?: presets[0] ?: presets.values.firstOrNull()

    /** Merged generator arrays for every sample that should sound for this note. */
    fun voices(bank: Int, program: Int, key: Int, vel: Int): List<IntArray> {
        val ck = (bank.toLong() shl 24) or (program.toLong() shl 16) or (key.toLong() shl 8) or vel.toLong()
        cache[ck]?.let { return it }
        val out = ArrayList<IntArray>()
        val p = findPreset(bank, program)
        if (p != null) {
            for (pz in p.zones) {
                if (!pz.matches(key, vel)) continue
                val instId = pz.gens[INSTRUMENT] ?: continue
                if (instId >= instruments.size) continue
                val inst = instruments[instId]
                for (iz in inst.zones) {
                    if (!iz.matches(key, vel)) continue
                    val g = defaults()
                    for ((op, v) in inst.global) if (op < NGEN) g[op] = v
                    for ((op, v) in iz.gens) if (op < NGEN) g[op] = v
                    val add = HashMap(p.global)
                    add.putAll(pz.gens)
                    for ((op, v) in add) if (op < NGEN && op !in NOT_ADDITIVE) g[op] += v
                    out.add(g)
                }
            }
        }
        cache[ck] = out
        return out
    }
}
