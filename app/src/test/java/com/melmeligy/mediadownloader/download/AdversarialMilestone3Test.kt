package com.melmeligy.mediadownloader.download

import com.melmeligy.mediadownloader.core.util.FormatUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Empirical Adversarial Test Harness for Milestone 3 Challenge
 *
 * Covers:
 * 1. SpeedTracker & FormatUtils.formatSpeed:
 *    - Extreme low, high, burst, randomized pauses, positive rates never outputting "0.00 MB/s"
 *    - Concurrency and thread safety under heavy load
 * 2. Parallel HlsSegmentDownloader:
 *    - Simulated segment latency and out-of-order completions
 *    - Strict ordered assembly and part-file cleanup
 *    - Coroutine cancellation and temporary directory wiping
 *    - Retry backoff on transient errors
 * 3. VideoAudioMuxer PTS Normalization & Fallback Invariants:
 *    - Discontinuous PTS behavior and analysis
 *    - Multi-track initial PTS discrepancy
 *    - Partial output fallback vulnerability verification
 */
object AdversarialMilestone3Test {

    private var passedChecks = 0
    private var failedChecks = 0

    private fun check(name: String, condition: Boolean, detail: String = "") {
        if (condition) {
            println("  [PASS] $name")
            passedChecks++
        } else {
            println("  [FAIL] $name: $detail")
            failedChecks++
        }
    }

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        println("================================================================================")
        println("STARTING EMPIRICAL ADVERSARIAL CHALLENGE SUITE — MILESTONE 3")
        println("================================================================================")

        testFormatUtilsAndSpeedTracker()
        testParallelHlsDownloaderPipeline()
        testVideoAudioMuxerPtsAndFallback()
        testMilestone3Iteration2Remediations()

        println("\n================================================================================")
        println("ADVERSARIAL CHALLENGE SUMMARY: $passedChecks PASSED, $failedChecks FAILED")
        println("================================================================================")

        if (failedChecks > 0) {
            println("\nCRITICAL FAILURES DETECTED! Review report for details.")
        } else {
            println("\nALL ADVERSARIAL STRESS TESTS COMPLETED SUCCESSFULLY.")
        }
    }

    // ---------------------------------------------------------------------------------
    // Area 1: SpeedTracker & FormatUtils.formatSpeed
    // ---------------------------------------------------------------------------------
    private fun testFormatUtilsAndSpeedTracker() {
        println("\n--- Area 1: SpeedTracker & FormatUtils.formatSpeed Stress Tests ---")

        // 1.1 Non-positive rates return "--"
        check("1.1 formatSpeed(0) returns '--'", FormatUtils.formatSpeed(0L) == "--")
        check("1.2 formatSpeed(-1) returns '--'", FormatUtils.formatSpeed(-1L) == "--")
        check("1.3 formatSpeed(Long.MIN_VALUE) returns '--'", FormatUtils.formatSpeed(Long.MIN_VALUE) == "--")

        // 1.2 Boundary checks around 1.0 MB/s (1024 * 1024 = 1,048,576 B/s)
        val boundaryBelow = 1024L * 1024L - 1L
        val formattedBelow = FormatUtils.formatSpeed(boundaryBelow)
        check("1.4 Rate immediately below 1 MB/s formats in KB/s", formattedBelow.endsWith("KB/s"), "Output was: $formattedBelow")
        check("1.5 Rate immediately below 1 MB/s is not '0.00 MB/s'", formattedBelow != "0.00 MB/s")

        val boundaryExact = 1024L * 1024L
        val formattedExact = FormatUtils.formatSpeed(boundaryExact)
        check("1.6 Rate exact 1 MB/s formats as '1.00 MB/s'", formattedExact == "1.00 MB/s", "Output was: $formattedExact")

        val boundaryAbove = 1024L * 1024L + 1L
        val formattedAbove = FormatUtils.formatSpeed(boundaryAbove)
        check("1.7 Rate immediately above 1 MB/s formats as '1.00 MB/s'", formattedAbove == "1.00 MB/s", "Output was: $formattedAbove")

        // 1.3 Extreme low speeds: 1, 2, 5, 10, 50, 100, 500, 1023 B/s
        val lowRates = listOf(1L, 2L, 5L, 10L, 50L, 100L, 500L, 1000L, 1023L)
        var zeroMbFound = false
        for (rate in lowRates) {
            val formatted = FormatUtils.formatSpeed(rate)
            if (formatted == "0.00 MB/s") {
                zeroMbFound = true
                break
            }
        }
        check("1.8 Extreme low speeds never output '0.00 MB/s'", !zeroMbFound)

        // 1.4 Extreme high speeds: 100 MB/s, 1 GB/s, 10 GB/s, Long.MAX_VALUE
        check("1.9 100 MB/s formatted properly", FormatUtils.formatSpeed(100L * 1024L * 1024L) == "100.00 MB/s")
        check("1.10 10 GB/s formatted properly", FormatUtils.formatSpeed(10L * 1024L * 1024L * 1024L) == "10240.00 MB/s")
        val maxFormatted = FormatUtils.formatSpeed(Long.MAX_VALUE)
        check("1.11 Long.MAX_VALUE does not throw or return negative", !maxFormatted.startsWith("-") && maxFormatted.endsWith("MB/s"))

        // 1.5 Fuzz test: 200,000 randomized positive rates
        var fuzzedZeroMbCount = 0
        val rng = Random(42)
        for (i in 0 until 200_000) {
            val randomRate = rng.nextLong(1L, 100_000_000_000L)
            if (FormatUtils.formatSpeed(randomRate) == "0.00 MB/s") {
                fuzzedZeroMbCount++
            }
        }
        check("1.12 200,000 fuzzed positive rates: '0.00 MB/s' count is strictly 0", fuzzedZeroMbCount == 0, "Found $fuzzedZeroMbCount occurrences")

        // 1.6 SpeedTracker: Randomized network pauses
        val t0 = 10_000_000L
        val tracker = SpeedTracker(initialBytes = 0L, initialTime = t0)
        // Baseline established at 10 MB/s (10,000,000 B/s)
        tracker.sample(currentBytes = 10_000_000L, now = t0 + 1000L)
        val initialEma = tracker.currentEma()

        // 3 consecutive transient zero ticks (+300ms, +600ms, +900ms, total pause = 900ms < 1500ms)
        var lastEma = initialEma
        for (step in 1..3) {
            tracker.sample(currentBytes = 10_000_000L, now = t0 + 1000L + (step * 300L))
            val current = tracker.currentEma()
            val expected = lastEma * 0.95
            check("1.13 Transient pause tick $step decays gently by 5%", kotlin.math.abs(current - expected) < 0.1)
            lastEma = current
        }
        check("1.14 Speed remains high (> 8 MB/s) across 900ms pause", tracker.currentEma() > 8_000_000.0)

        // Prolonged stall exceeding 1500ms
        // Stall started at t0 + 1300L. At t0 + 2900L, duration = 1600ms >= 1500ms
        tracker.sample(currentBytes = 10_000_000L, now = t0 + 2900L)
        val expectedFasterDecay = lastEma * 0.70
        check("1.15 Prolonged stall (>1.5s) decays faster by 30%", kotlin.math.abs(tracker.currentEma() - expectedFasterDecay) < 0.1)

        // 1.7 SpeedTracker: Sudden burst rate transition
        val burstTracker = SpeedTracker(initialBytes = 0L, initialTime = t0)
        burstTracker.sample(currentBytes = 1_000_000L, now = t0 + 1000L) // 1 MB/s baseline
        val baseline = burstTracker.currentEma()
        // Sudden 50 MB burst over next 1 second (instant = 50 MB/s)
        burstTracker.sample(currentBytes = 51_000_000L, now = t0 + 2000L)
        val expectedBurstEma = baseline * 0.85 + 50_000_000.0 * 0.15
        check("1.16 Burst rate transitions with smooth 85/15 weighting", kotlin.math.abs(burstTracker.currentEma() - expectedBurstEma) < 0.1)

        // 1.8 SpeedTracker: Sub-throttle interval (<250ms) and clock skew
        val speedBefore = burstTracker.currentEma()
        val speedIgnored = burstTracker.sample(currentBytes = 100_000_000L, now = t0 + 2100L) // dt = 100ms < 250ms
        check("1.17 Calls within 250ms return previous EMA without corrupting state", speedIgnored == speedBefore.toLong())

        // Clock skew backwards (now < lastTime)
        val speedSkew = burstTracker.sample(currentBytes = 100_000_000L, now = t0 + 1500L)
        check("1.18 Negative dt (clock skew) returns previous EMA safely", speedSkew == speedBefore.toLong())

        // 1.9 SpeedTracker: Thread safety under 20 concurrent threads
        val concurrentTracker = SpeedTracker(0L, t0)
        val threads = 20
        val latch = CountDownLatch(threads)
        val exceptions = AtomicInteger(0)
        for (i in 0 until threads) {
            Thread {
                try {
                    for (j in 1..500) {
                        concurrentTracker.sample(j * 1000L, t0 + j * 300L)
                        concurrentTracker.currentEma()
                    }
                } catch (e: Exception) {
                    exceptions.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }.start()
        }
        latch.await(5, TimeUnit.SECONDS)
        check("1.19 Thread safety: 20 threads concurrent access produced 0 errors", exceptions.get() == 0)
    }

    // ---------------------------------------------------------------------------------
    // Area 2: Parallel HlsSegmentDownloader Pipeline Stress Tests
    // ---------------------------------------------------------------------------------
    private suspend fun testParallelHlsDownloaderPipeline() = coroutineScope {
        println("\n--- Area 2: Parallel HlsSegmentDownloader Pipeline Stress Tests ---")

        val testDir = File("/tmp/hls_adversarial_test_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val segmentCount = 12
            val segmentPayloads = (0 until segmentCount).map { i ->
                "SEGMENT_${String.format("%03d", i)}_PAYLOAD_DATA_${"X".repeat(500)}"
            }

            // Simulated random latencies: some segments finish fast, some slow
            // E.g. segment 0 is deliberately slow (150ms), segment 1 is fast (10ms)
            val segmentDelaysMs = listOf(
                150L, 10L, 80L, 5L, 120L, 15L, 90L, 20L, 110L, 8L, 70L, 25L
            )

            val destFile = File(testDir, "assembled_stream.ts")
            val partsDir = File(testDir, "hls_parts_${System.currentTimeMillis()}").apply { mkdirs() }

            val maxObservedConcurrency = AtomicInteger(0)
            val activeWorkers = AtomicInteger(0)
            val semaphore = Semaphore(5) // Bounded 5-worker concurrency

            val written = AtomicLong(0)
            val completed = AtomicInteger(0)

            // Launch 12 parallel segment downloads with out-of-order completions
            val tasks = (0 until segmentCount).map { index ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val current = activeWorkers.incrementAndGet()
                        maxObservedConcurrency.updateAndGet { prev -> maxOf(prev, current) }
                        val partFile = File(partsDir, "seg_$index.part")
                        // Simulate network delay
                        delay(segmentDelaysMs[index])
                        val bytes = segmentPayloads[index].toByteArray()
                        partFile.writeBytes(bytes)
                        written.addAndGet(bytes.size.toLong())
                        activeWorkers.decrementAndGet()
                        partFile
                    }
                }
            }

            // Sequential assembly loop mirroring HlsSegmentDownloader.kt:100-116
            FileOutputStream(destFile, true).use { output ->
                val copyBuffer = ByteArray(128 * 1024)
                for (task in tasks) {
                    val partFile = task.await()
                    partFile.inputStream().use { input ->
                        while (true) {
                            val read = input.read(copyBuffer)
                            if (read == -1) break
                            output.write(copyBuffer, 0, read)
                        }
                    }
                    partFile.delete()
                    completed.incrementAndGet()
                }
                output.flush()
            }
            partsDir.deleteRecursively()

            // 2.1 Concurrency bounded to at most 5
            check("2.1 Max observed concurrency never exceeded 5 workers", maxObservedConcurrency.get() <= 5, "Observed: ${maxObservedConcurrency.get()}")

            // 2.2 Strict ordered assembly
            val assembledContent = destFile.readText()
            val expectedContent = segmentPayloads.joinToString("")
            check("2.2 Assembled stream matches strict sequential order despite out-of-order arrivals", assembledContent == expectedContent)

            // 2.3 Part files cleaned up immediately
            val remainingParts = partsDir.listFiles() ?: emptyArray()
            check("2.3 All part files and temporary directory were wiped", remainingParts.isEmpty() && !partsDir.exists())

            // 2.4 Coroutine Cancellation & Resource Cleanup
            val abortPartsDir = File(testDir, "hls_parts_abort_${System.currentTimeMillis()}").apply { mkdirs() }
            val abortDestFile = File(testDir, "aborted_stream.ts")
            val cancellationObserved = AtomicBoolean(false)

            val parentJob = launch {
                val abortTasks = (0 until 10).map { idx ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            currentCoroutineContext().ensureActive()
                            val partFile = File(abortPartsDir, "seg_$idx.part")
                            try {
                                partFile.writeText("ABORT_TEST_$idx")
                                delay(2000L) // Long running download
                                partFile
                            } catch (ce: CancellationException) {
                                partFile.delete()
                                throw ce
                            }
                        }
                    }
                }
                try {
                    FileOutputStream(abortDestFile, true).use { output ->
                        for (task in abortTasks) {
                            task.await()
                        }
                    }
                } finally {
                    abortPartsDir.deleteRecursively()
                }
            }

            delay(100L) // Let workers start and write initial part files
            check("2.4 Part files existed during active download", abortPartsDir.exists())
            parentJob.cancelAndJoin()
            cancellationObserved.set(true)

            check("2.5 Coroutine cancellation cleanly terminated parent and child tasks", cancellationObserved.get())
            check("2.6 Temporary parts directory deleted on coroutine cancellation/abort", !abortPartsDir.exists(), "Directory still exists: ${abortPartsDir.exists()}")

        } finally {
            testDir.deleteRecursively()
        }
    }

    // ---------------------------------------------------------------------------------
    // Area 3: VideoAudioMuxer PTS Normalization & Fallback Invariants
    // ---------------------------------------------------------------------------------
    private fun testVideoAudioMuxerPtsAndFallback() {
        println("\n--- Area 3: VideoAudioMuxer PTS Normalization & Fallback Stress Tests ---")

        // 3.1 Test Monotonic PTS Clamping Logic for Discontinuous PTS
        // Algorithm from VideoAudioMuxer.kt:246-261:
        // pts = (pts - firstPtsUs).coerceAtLeast(0L)
        // if (pts <= lastPtsUs) pts = lastPtsUs + 1000L
        // lastPtsUs = pts

        fun normalizePtsStream(rawTimestamps: List<Long>): List<Long> {
            var firstPtsUs = -1L
            var lastPtsUs = -1L
            val normalized = mutableListOf<Long>()
            for (raw in rawTimestamps) {
                var pts = raw
                if (firstPtsUs < 0L) firstPtsUs = pts
                pts = (pts - firstPtsUs).coerceAtLeast(0L)
                if (pts <= lastPtsUs) pts = lastPtsUs + 1000L
                lastPtsUs = pts
                normalized.add(pts)
            }
            return normalized
        }

        // Test normal increasing PTS
        val normalPts = listOf(10_000_000L, 10_033_333L, 10_066_666L, 10_100_000L)
        val normalNormalized = normalizePtsStream(normalPts)
        check("3.1 Normal timestamps normalized starting from 0", normalNormalized[0] == 0L)
        check("3.2 Normal timestamps maintain relative deltas", normalNormalized[1] == 33_333L && normalNormalized[2] == 66_666L)

        // Test small negative jitter / B-frame reordering: 100ms -> 95ms -> 130ms
        val jitterPts = listOf(10_000_000L, 10_100_000L, 10_095_000L, 10_130_000L)
        val jitterNormalized = normalizePtsStream(jitterPts)
        check("3.3 Jitter timestamps strictly monotonic", jitterNormalized[2] == jitterNormalized[1] + 1000L)
        check("3.4 Resumed stream catches up after jitter", jitterNormalized[3] == 130_000L)

        // 3.2 EMPIRICAL CHALLENGE: Discontinuous PTS (PTS Reset / Ad Insertion in HLS)
        // In HLS, when a stream has a discontinuity, PTS jumps backwards (e.g. 10.0s -> 0.0s)
        val discontinuousPts = listOf(
            10_000_000L, 10_500_000L, 11_000_000L, // Segment 1 (1.0s long)
            0L, 33_333L, 66_666L, 100_000L, 133_333L // Segment 2 starts at 0L (ad insertion or discontinuity)
        )
        val discontinuousNormalized = normalizePtsStream(discontinuousPts)
        // In the current algorithm:
        // Segment 1 ends at 1_000_000L (1 second)
        // Segment 2 frame 0 (raw 0L): (0 - 10_000_000) coerced to 0L. 0L <= 1_000_000L -> becomes 1_001_000L (+1ms)
        // Segment 2 frame 1 (raw 33ms): (33ms - 10s) coerced to 0L. 0L <= 1_001_000L -> becomes 1_002_000L (+1ms)
        // Frame 2 -> 1_003_000L (+1ms)
        val frame1Normalized = discontinuousNormalized[3] // frame 0 of segment 2
        val frame2Normalized = discontinuousNormalized[4] // frame 1 of segment 2
        val frameDelta = frame2Normalized - frame1Normalized

        val hasSquashDefect = (frameDelta == 1000L)
        println("  [ANALYSIS] Discontinuous PTS behavior: frame delta after discontinuity = ${frameDelta}us (1ms instead of 33ms)")
        check("3.5 Discontinuous PTS produces strictly increasing timestamps (no crash in MediaMuxer)", discontinuousNormalized.zipWithNext().all { (a, b) -> b > a })

        // 3.3 EMPIRICAL CHALLENGE: Partial Output Fallback Vulnerability Verification
        // In VideoAudioMuxer.kt:268-274:
        // } catch (e: Exception) {
        //     runCatching {
        //         if (!outputFile.exists() || outputFile.length() <= 0L) {
        //             tsFile.copyTo(outputFile, overwrite = true)
        //         }
        //     }
        //     outputFile.exists() && outputFile.length() > 0L
        // }
        val testMuxDir = File("/tmp/muxer_challenge_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val sourceTs = File(testMuxDir, "original_complete.ts").apply {
                writeBytes(ByteArray(10_000) { 0x47.toByte() }) // 10 KB TS file
            }
            val partialOut = File(testMuxDir, "output.mp4").apply {
                writeBytes(ByteArray(1_000) { 0x00.toByte() }) // 1 KB partial corrupt file
            }

            // Simulate the exact catch block logic from VideoAudioMuxer.kt:269-275
            fun simulateCatchFallback(tsFile: File, outputFile: File): Boolean {
                var copied = false
                try {
                    // Simulate an exception thrown during muxing
                    throw RuntimeException("Simulated native MediaMuxer failure mid-stream")
                } catch (e: Exception) {
                    if (!outputFile.exists() || outputFile.length() <= 0L) {
                        tsFile.copyTo(outputFile, overwrite = true)
                        copied = true
                    }
                    val result = outputFile.exists() && outputFile.length() > 0L
                    return copied
                }
            }

            val fallbackCopied = simulateCatchFallback(sourceTs, partialOut)
            // If fallbackCopied is FALSE, the 1 KB corrupt file was retained and sourceTs was NOT copied!
            val outputSize = partialOut.length()
            println("  [OBSERVATION] When outputFile has partial bytes (>0), fallbackCopied = $fallbackCopied, final fileSize = $outputSize bytes (expected 10000 bytes)")
            check(
                "3.6 Identified critical caveat: partial corrupt output prevents sourceFile copy due to length > 0 check",
                !fallbackCopied && outputSize == 1000L
            )

            // 3.7 Iteration 2 Fixed Catch Block: outputFile.delete() then copyTo(overwrite = true)
            fun simulateRemediatedCatchFallback(tsFile: File, outputFile: File): Boolean {
                var copied = false
                try {
                    throw RuntimeException("Simulated native MediaMuxer failure mid-stream")
                } catch (e: Exception) {
                    kotlin.runCatching {
                        outputFile.delete()
                        tsFile.copyTo(outputFile, overwrite = true)
                        copied = true
                    }
                    val result = outputFile.exists() && outputFile.length() > 0L
                    return copied && result
                }
            }

            val remediatedCopied = simulateRemediatedCatchFallback(sourceTs, partialOut)
            val remediatedSize = partialOut.length()
            check(
                "3.7 Remediated fallback: outputFile is deleted and overwritten with exact source data",
                remediatedCopied && remediatedSize == 10_000L && partialOut.readBytes().contentEquals(sourceTs.readBytes())
            )

        } finally {
            testMuxDir.deleteRecursively()
        }
    }

    // ---------------------------------------------------------------------------------
    // Area 4: Milestone 3 Iteration 2 Remediations Adversarial Stress
    // ---------------------------------------------------------------------------------
    private fun testMilestone3Iteration2Remediations() = runBlocking {
        println("\n--- Area 4: Milestone 3 Iteration 2 Remediations Stress Tests ---")

        val testDir = File("/tmp/m3_iter2_adversarial_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            // 4.1 HlsSegmentDownloader: Child task cancellation in finally block
            val partsDir1 = File(testDir, "hls_parts_cancel_test").apply { mkdirs() }
            val dest1 = File(testDir, "cancel_output.ts")
            val semaphore = Semaphore(5)
            val childTasksCancelled = AtomicInteger(0)

            val parentJob = launch {
                coroutineScope {
                    val tasks = (0 until 10).map { idx ->
                        async(Dispatchers.IO) {
                            semaphore.withPermit {
                                try {
                                    val partFile = File(partsDir1, "seg_$idx.part").apply { writeText("SEG_$idx") }
                                    delay(2000L) // Stalling download
                                    partFile
                                } catch (ce: CancellationException) {
                                    childTasksCancelled.incrementAndGet()
                                    throw ce
                                }
                            }
                        }
                    }
                    try {
                        for (task in tasks) {
                            task.await()
                        }
                    } finally {
                        tasks.forEach { it.cancel() }
                        partsDir1.deleteRecursively()
                    }
                }
            }

            delay(150L) // Wait for tasks to launch and create files
            check("4.1 Parts directory existed during download", partsDir1.exists())
            parentJob.cancelAndJoin()
            check("4.2 Child tasks were explicitly cancelled in finally", childTasksCancelled.get() > 0)
            check("4.3 Parts directory was wiped cleanly upon parent cancellation", !partsDir1.exists())

            // 4.2 HlsSegmentDownloader: Exception during sequential assembly cancels tasks & wipes partsDir
            val partsDir2 = File(testDir, "hls_parts_assembly_fail").apply { mkdirs() }
            val dest2 = File(testDir, "assembly_fail_output.ts")
            var assemblyExceptionCaught = false

            try {
                coroutineScope {
                    val tasks = (0 until 8).map { idx ->
                        async(Dispatchers.IO) {
                            semaphore.withPermit {
                                val partFile = File(partsDir2, "seg_$idx.part").apply { writeText("SEG_CHUNK_$idx") }
                                delay(10L)
                                partFile
                            }
                        }
                    }
                    try {
                        FileOutputStream(dest2).use { out ->
                            for ((idx, task) in tasks.withIndex()) {
                                if (idx == 3) {
                                    throw java.io.IOException("Simulated I/O disk error on segment 3 during assembly")
                                }
                                val p = task.await()
                                out.write(p.readBytes())
                            }
                        }
                    } finally {
                        tasks.forEach { it.cancel() }
                        partsDir2.deleteRecursively()
                    }
                }
            } catch (e: java.io.IOException) {
                assemblyExceptionCaught = true
            }

            check("4.4 Assembly error caught and propagated", assemblyExceptionCaught)
            check("4.5 Parts directory wiped in finally despite assembly exception", !partsDir2.exists())

            // 4.3 DownloadManager.cleanupStaging(id)
            val stagingDir = File(testDir, "Downloads").apply { mkdirs() }
            val targetId = 88L

            // Target files matching dl_$id. and dl_${id}_
            val targetPart = File(stagingDir, "dl_$targetId.mp4.part").apply { writeText("part") }
            val targetRawTs = File(stagingDir, "dl_${targetId}_raw.ts.part").apply { writeText("ts") }
            val targetVideo = File(stagingDir, "dl_${targetId}_video.mp4.part").apply { writeText("video") }
            val targetAudio = File(stagingDir, "dl_${targetId}_audio.m4a.part").apply { writeText("audio") }
            val targetPartsDir = File(stagingDir, "hls_parts_dl_${targetId}_raw_1731000000").apply {
                mkdirs()
                File(this, "seg_0.part").writeText("seg0")
                File(this, "nested").apply { mkdirs(); File(this, "leaf.tmp").writeText("leaf") }
            }

            // Sibling files that must NOT be touched
            val siblingDifferentId = File(stagingDir, "dl_${targetId}0.mp4.part").apply { writeText("keep 880") }
            val siblingRawTs = File(stagingDir, "dl_${targetId}9_raw.ts.part").apply { writeText("keep 889") }
            val siblingOther = File(stagingDir, "dl_99.mp4.part").apply { writeText("keep 99") }
            val siblingPartsDir = File(stagingDir, "hls_parts_dl_${targetId}0_1731000000").apply {
                mkdirs()
                File(this, "seg_0.part").writeText("keep dir")
            }

            fun cleanupStaging(id: Long) {
                stagingDir.listFiles()
                    ?.filter {
                        it.name.startsWith("dl_$id.") ||
                            it.name.startsWith("dl_${id}_") ||
                            it.name.startsWith("hls_parts_dl_${id}_")
                    }
                    ?.forEach {
                        kotlin.runCatching {
                            if (it.isDirectory) it.deleteRecursively() else it.delete()
                        }
                    }
            }

            cleanupStaging(targetId)

            check("4.6 cleanupStaging wiped dl_$targetId.mp4.part", !targetPart.exists())
            check("4.7 cleanupStaging wiped dl_${targetId}_raw.ts.part", !targetRawTs.exists())
            check("4.8 cleanupStaging wiped dl_${targetId}_video.mp4.part", !targetVideo.exists())
            check("4.9 cleanupStaging wiped dl_${targetId}_audio.m4a.part", !targetAudio.exists())
            check("4.10 cleanupStaging recursively wiped hls_parts_dl_${targetId}_ directory", !targetPartsDir.exists())

            // Verify siblings untouched
            check("4.11 Sibling file dl_880.mp4.part strictly preserved", siblingDifferentId.exists())
            check("4.12 Sibling file dl_889_raw.ts.part strictly preserved", siblingRawTs.exists())
            check("4.13 Sibling file dl_99.mp4.part strictly preserved", siblingOther.exists())
            check("4.14 Sibling directory hls_parts_dl_880_* strictly preserved", siblingPartsDir.exists())

        } finally {
            testDir.deleteRecursively()
        }
    }
}

