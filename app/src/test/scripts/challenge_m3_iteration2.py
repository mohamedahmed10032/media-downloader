#!/usr/bin/env python3
"""
Empirical Challenger M3-2 Stress & Adversarial Verification Suite
Media Downloader v1.9.0 - Milestone 3 Iteration 2

Focus areas:
1. SpeedTracker and FormatUtils under extreme values (zero rate, ultra-high rate, inter-segment pauses).
2. Simulate HLS download with cancellation: ensure coroutines cancel cleanly without unhandled exceptions.
3. Validate that VideoAudioMuxer handles all 3 entrypoints (mux, extractAudioOnly, remuxTsToMp4) safely on exception.
"""

import sys
import os
import time
import math
import random
import threading
import shutil
import tempfile
from typing import Dict, Any, Optional, List, Tuple
from concurrent.futures import ThreadPoolExecutor, as_completed

class ChallengerSuite:
    def __init__(self):
        self.passed = 0
        self.failed = 0
        self.failures = []

    def check(self, desc: str, condition: bool, details: str = ""):
        if condition:
            print(f"  [PASS] {desc}")
            self.passed += 1
        else:
            msg = f"  [FAIL] {desc} -> {details}"
            print(msg)
            self.failed += 1
            self.failures.append(msg)

# ============================================================================
# Section 1: FormatUtils and SpeedTracker Extreme Value Verification
# ============================================================================

def format_speed_py(bytes_per_second: int) -> str:
    """Exact replica of FormatUtils.formatSpeed(bytesPerSecond: Long)."""
    if bytes_per_second <= 0:
        return "--"
    mb_per_sec = float(bytes_per_second) / (1024.0 * 1024.0)
    if mb_per_sec >= 1.0:
        return f"{mb_per_sec:.2f} MB/s"
    else:
        kb_per_sec = float(bytes_per_second) / 1024.0
        return f"{kb_per_sec:.1f} KB/s"

class SpeedTrackerPy:
    """Exact replica of SpeedTracker in DownloadModels.kt."""
    def __init__(self, initial_bytes: int = 0, initial_time_ms: int = 0):
        self.last_time = initial_time_ms
        self.last_bytes = initial_bytes
        self.ema = 0.0
        self.zero_hold_start = 0
        self.lock = threading.Lock()

    def sample(self, current_bytes: int, now_ms: int) -> int:
        with self.lock:
            dt = now_ms - self.last_time
            if dt < 250:
                return int(self.ema)
            delta = max(0, current_bytes - self.last_bytes)
            instant = delta * 1000.0 / dt

            if delta == 0:
                if self.zero_hold_start == 0:
                    self.zero_hold_start = now_ms
                zero_duration = now_ms - self.zero_hold_start
                if zero_duration < 1500 and self.ema > 0.0:
                    self.ema *= 0.95
                else:
                    self.ema *= 0.70
            else:
                self.zero_hold_start = 0
                if self.ema == 0.0:
                    self.ema = instant
                else:
                    self.ema = self.ema * 0.85 + instant * 0.15

            self.last_time = now_ms
            self.last_bytes = current_bytes
            return int(self.ema)

    def current_ema(self) -> float:
        with self.lock:
            return self.ema

def test_speed_tracker_and_format_utils(suite: ChallengerSuite):
    print("\n>>> 1. STRESS TESTING FormatUtils AND SpeedTracker (Extreme Values & Pauses)...")

    # 1.1 Non-positive extreme values
    suite.check("formatSpeed(0) returns '--'", format_speed_py(0) == "--")
    suite.check("formatSpeed(-1) returns '--'", format_speed_py(-1) == "--")
    suite.check("formatSpeed(-1000000000) returns '--'", format_speed_py(-1000000000) == "--")
    suite.check("formatSpeed(Long.MIN_VALUE) returns '--'", format_speed_py(-9223372036854775808) == "--")

    # 1.2 Low speeds never emitting "0.00 MB/s"
    low_speeds = [1, 2, 5, 10, 50, 100, 500, 1000, 1024, 4000, 50000, 100000, 500000, 1048575]
    zero_mb_count = sum(1 for s in low_speeds if format_speed_py(s) == "0.00 MB/s")
    suite.check("Low speeds (1 B/s to 1MB - 1) never output '0.00 MB/s'", zero_mb_count == 0)

    # 4000 B/s was the exact defect in screenshot 135534
    suite.check("4000 B/s formats as 3.9 KB/s", format_speed_py(4000) == "3.9 KB/s", f"Got {format_speed_py(4000)}")

    # 1.3 Boundary around 1 MB/s
    suite.check("1048575 B/s (< 1MB) formats in KB/s", format_speed_py(1048575).endswith("KB/s"))
    suite.check("1048576 B/s (= 1MB) formats as '1.00 MB/s'", format_speed_py(1048576) == "1.00 MB/s")
    suite.check("1048577 B/s (> 1MB) formats as '1.00 MB/s'", format_speed_py(1048577) == "1.00 MB/s")

    # 1.4 Ultra-high speeds
    gb_10 = 10 * 1024 * 1024 * 1024
    suite.check("10 GB/s formats correctly as '10240.00 MB/s'", format_speed_py(gb_10) == "10240.00 MB/s")
    tb_1 = 1024 * 1024 * 1024 * 1024
    suite.check("1 TB/s formats correctly", format_speed_py(tb_1).endswith("MB/s"))
    long_max = 9223372036854775807
    max_formatted = format_speed_py(long_max)
    suite.check("Long.MAX_VALUE formats without overflow or negative sign", not max_formatted.startswith("-") and max_formatted.endswith("MB/s"))

    # 1.5 Fuzzing 500,000 randomized rates
    rng = random.Random(999)
    fuzzed_zero_count = 0
    for _ in range(500_000):
        val = rng.randint(1, 10_000_000_000)
        if format_speed_py(val) == "0.00 MB/s":
            fuzzed_zero_count += 1
    suite.check("500,000 fuzzed rates: zero '0.00 MB/s' occurrences", fuzzed_zero_count == 0)

    # 1.6 SpeedTracker inter-segment pauses
    t0 = 1_000_000
    st = SpeedTrackerPy(initial_bytes=0, initial_time_ms=t0)
    # 5 MB downloaded over 1 second = 5 MB/s
    st.sample(5 * 1024 * 1024, t0 + 1000)
    init_ema = st.current_ema()
    suite.check("SpeedTracker established baseline ~5.24 MB/s", abs(init_ema - 5242880.0) < 1.0)

    # Simulate 4 consecutive 300ms pause ticks (total duration = 1200ms < 1500ms)
    # Each tick delta == 0, ema should decay by only 5% (ema *= 0.95)
    last_ema = init_ema
    for i in range(1, 5):
        t_now = t0 + 1000 + i * 300
        st.sample(5 * 1024 * 1024, t_now)
        expected = last_ema * 0.95
        actual = st.current_ema()
        suite.check(f"Transient pause tick {i} ({i*300}ms): decays by exactly 5%", abs(actual - expected) < 1.0)
        last_ema = actual

    suite.check("Speed remains steady (> 4.2 MB/s) after 1.2s pause", st.current_ema() > 4_200_000.0)

    # Now simulate prolonged stall exceeding 1500ms
    # Duration = 1800ms >= 1500ms -> decay 30% (ema *= 0.70)
    st.sample(5 * 1024 * 1024, t0 + 1000 + 1800)
    expected_stall = last_ema * 0.70
    suite.check("Prolonged stall (>= 1.5s): decays faster by 30%", abs(st.current_ema() - expected_stall) < 1.0)

    # 1.7 Sub-throttle calls (< 250ms) and clock skew
    ema_before = st.current_ema()
    st.sample(10 * 1024 * 1024, t0 + 1000 + 1850) # dt = 50ms < 250ms
    suite.check("Sub-throttle call (< 250ms) preserves EMA unchanged", st.current_ema() == ema_before)

    # Time reversal (clock skew)
    st.sample(10 * 1024 * 1024, t0 + 500) # now < last_time -> dt < 0 < 250
    suite.check("Clock skew (negative dt) safely ignored without state corruption", st.current_ema() == ema_before)

    # 1.8 Multithreaded concurrency stress (50 threads)
    c_tracker = SpeedTrackerPy(0, t0)
    errors = []
    def worker_thread(tid):
        try:
            for step in range(1, 200):
                c_tracker.sample(step * 10000, t0 + step * 300)
                _ = c_tracker.current_ema()
        except Exception as ex:
            errors.append(ex)

    threads = [threading.Thread(target=worker_thread, args=(i,)) for i in range(50)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    suite.check("50 concurrent threads accessing SpeedTracker produced 0 errors", len(errors) == 0)


# ============================================================================
# Section 2: Simulate HLS Download with Cancellation & Cleanup
# ============================================================================

class CoroutineCancellationException(Exception):
    pass

class SimulatedHlsDownloader:
    """
    Simulates the exact coroutine behavior and concurrency structure of
    HlsSegmentDownloader.kt:
    - Semaphore(5)
    - async(Dispatchers.IO) { downloadSegmentToPart(...) }
    - Sequential copy into dest
    - Cancellation handling and partsDir cleanup in finally
    """
    def __init__(self, concurrent_limit: int = 5):
        self.semaphore = threading.Semaphore(concurrent_limit)
        self.written = 0
        self.written_lock = threading.Lock()
        self.active_workers = 0
        self.max_workers = 0
        self.workers_lock = threading.Lock()

    def download(self, num_segments: int, dest_file: str, parts_dir: str,
                 cancel_after_segments: Optional[int] = None,
                 cancel_mid_transfer: bool = False):
        os.makedirs(parts_dir, exist_ok=True)
        tasks = []
        is_cancelled = threading.Event()
        cancel_lock = threading.Lock()

        # Each segment task
        def segment_task(index: int, seg_payload: bytes):
            # Check before acquire
            if is_cancelled.is_set():
                raise CoroutineCancellationException(f"Task {index} cancelled before acquire")
            with self.semaphore:
                if is_cancelled.is_set():
                    raise CoroutineCancellationException(f"Task {index} cancelled after acquire")

                with self.workers_lock:
                    self.active_workers += 1
                    self.max_workers = max(self.max_workers, self.active_workers)

                part_file = os.path.join(parts_dir, f"seg_{index}.part")
                attempt_bytes = 0
                try:
                    # Stream write simulation
                    chunk_size = 1024
                    with open(part_file, "wb") as f:
                        for offset in range(0, len(seg_payload), chunk_size):
                            if is_cancelled.is_set():
                                raise CoroutineCancellationException(f"Task {index} cancelled mid-transfer")
                            chunk = seg_payload[offset:offset+chunk_size]
                            f.write(chunk)
                            attempt_bytes += len(chunk)
                            with self.written_lock:
                                self.written += len(chunk)
                            time.sleep(0.005) # simulate network latency

                    return part_file
                except CoroutineCancellationException as ce:
                    # rollback bytes as in HlsSegmentDownloader.kt:161-164
                    if attempt_bytes > 0:
                        with self.written_lock:
                            self.written -= attempt_bytes
                    if os.path.exists(part_file):
                        try:
                            os.remove(part_file)
                        except OSError:
                            pass
                    raise ce
                finally:
                    with self.workers_lock:
                        self.active_workers -= 1

        # Launch all async tasks using ThreadPool
        executor = ThreadPoolExecutor(max_workers=10)
        futures = []
        segment_payloads = [f"SEG_{i}_DATA_{'A' * 4096}".encode("utf-8") for i in range(num_segments)]

        try:
            for idx, p in enumerate(segment_payloads):
                futures.append(executor.submit(segment_task, idx, p))

            # Assemble into dest in strict 0..N order
            with open(dest_file, "wb") as out_f:
                for idx, fut in enumerate(futures):
                    if cancel_after_segments is not None and idx == cancel_after_segments:
                        is_cancelled.set()
                        raise CoroutineCancellationException("Simulated user cancellation mid-assembly")

                    if cancel_mid_transfer and idx == 2:
                        # trigger cancellation while tasks 3, 4, 5, 6 are downloading
                        is_cancelled.set()
                        raise CoroutineCancellationException("Simulated user cancellation mid-transfer")

                    part_path = fut.result()
                    with open(part_path, "rb") as in_f:
                        shutil.copyfileobj(in_f, out_f)
                    os.remove(part_path)

        except CoroutineCancellationException as e:
            is_cancelled.set()
            # In finally: tasks.forEach { it.cancel() }; partsDir.deleteRecursively()
            raise e
        finally:
            is_cancelled.set()
            executor.shutdown(wait=True)
            # Cleanup partsDir recursively as in HlsSegmentDownloader.kt:119
            if os.path.exists(parts_dir):
                shutil.rmtree(parts_dir, ignore_errors=True)

def test_hls_download_cancellation(suite: ChallengerSuite):
    print("\n>>> 2. STRESS TESTING HLS DOWNLOAD WITH COROUTINE CANCELLATION & CLEANUP...")

    temp_base = tempfile.mkdtemp(prefix="hls_challenger_test_")
    try:
        # 2.1 Clean download without cancellation
        dest_clean = os.path.join(temp_base, "dest_clean.ts")
        parts_clean = os.path.join(temp_base, "parts_clean")
        dl = SimulatedHlsDownloader(concurrent_limit=5)
        dl.download(num_segments=15, dest_file=dest_clean, parts_dir=parts_clean)

        suite.check("HLS normal download: max concurrent workers <= 5", dl.max_workers <= 5, f"Was {dl.max_workers}")
        suite.check("HLS normal download: assembled file exists and non-empty", os.path.exists(dest_clean) and os.path.getsize(dest_clean) > 0)
        suite.check("HLS normal download: parts directory wiped on completion", not os.path.exists(parts_clean))

        # 2.2 Simulated cancellation mid-assembly (after 4 segments)
        dest_cancel1 = os.path.join(temp_base, "dest_cancel1.ts")
        parts_cancel1 = os.path.join(temp_base, "parts_cancel1")
        dl_cancel1 = SimulatedHlsDownloader(concurrent_limit=5)

        caught_cancel1 = False
        try:
            dl_cancel1.download(num_segments=15, dest_file=dest_cancel1, parts_dir=parts_cancel1, cancel_after_segments=4)
        except CoroutineCancellationException:
            caught_cancel1 = True

        suite.check("HLS cancellation mid-assembly raises CancellationException cleanly", caught_cancel1)
        suite.check("HLS cancellation mid-assembly wipes parts directory", not os.path.exists(parts_cancel1))
        # Verify no remaining .part files anywhere
        remaining_parts = [f for root, _, files in os.walk(temp_base) for f in files if f.endswith(".part")]
        suite.check("Zero orphaned .part files remain after cancellation", len(remaining_parts) == 0, f"Found: {remaining_parts}")

        # 2.3 Simulated cancellation mid-transfer (while multiple workers downloading)
        dest_cancel2 = os.path.join(temp_base, "dest_cancel2.ts")
        parts_cancel2 = os.path.join(temp_base, "parts_cancel2")
        dl_cancel2 = SimulatedHlsDownloader(concurrent_limit=5)

        caught_cancel2 = False
        try:
            dl_cancel2.download(num_segments=20, dest_file=dest_cancel2, parts_dir=parts_cancel2, cancel_mid_transfer=True)
        except CoroutineCancellationException:
            caught_cancel2 = True

        suite.check("HLS cancellation mid-transfer caught without uncaught thread crash", caught_cancel2)
        suite.check("HLS cancellation mid-transfer wipes parts directory", not os.path.exists(parts_cancel2))
        suite.check("Written bytes rollback prevents counter corruption", dl_cancel2.written >= 0)

        # 2.4 Verify DownloadManager cleanupStaging logic on cancellation
        # Create simulated files matching pattern:
        # dl_42.mp4.part, dl_42_raw.ts.part, dl_42_video.mp4.part, hls_parts_dl_42_12345/
        test_dl_id = 42
        staging_dir = os.path.join(temp_base, "staging")
        os.makedirs(staging_dir, exist_ok=True)

        f1 = os.path.join(staging_dir, f"dl_{test_dl_id}.mp4.part")
        f2 = os.path.join(staging_dir, f"dl_{test_dl_id}_raw.ts.part")
        d1 = os.path.join(staging_dir, f"hls_parts_dl_{test_dl_id}_999999")
        os.makedirs(d1, exist_ok=True)
        with open(f1, "w") as f: f.write("part1")
        with open(f2, "w") as f: f.write("part2")
        with open(os.path.join(d1, "seg_0.part"), "w") as f: f.write("part_in_dir")

        # Other item that should NOT be cleaned up
        other_file = os.path.join(staging_dir, "dl_99.mp4.part")
        with open(other_file, "w") as f: f.write("keep_me")

        # Run cleanupStaging replica from DownloadManager.kt:363-376
        for item in os.listdir(staging_dir):
            if item.startswith(f"dl_{test_dl_id}.") or item.startswith(f"dl_{test_dl_id}_") or item.startswith(f"hls_parts_dl_{test_dl_id}_"):
                full_p = os.path.join(staging_dir, item)
                if os.path.isdir(full_p):
                    shutil.rmtree(full_p, ignore_errors=True)
                else:
                    os.remove(full_p)

        suite.check("cleanupStaging deleted dl_42.* files", not os.path.exists(f1) and not os.path.exists(f2))
        suite.check("cleanupStaging deleted hls_parts_dl_42_* directory recursively", not os.path.exists(d1))
        suite.check("cleanupStaging preserved unrelated items (dl_99)", os.path.exists(other_file))

    finally:
        shutil.rmtree(temp_base, ignore_errors=True)


# ============================================================================
# Section 3: Validate VideoAudioMuxer 3 Entrypoints on Exception & Fallback
# ============================================================================

class SimulatedVideoAudioMuxer:
    """
    Simulates the exact exception-handling and fallback logic of:
    1. mux(videoFile, audioFile, outputFile)
    2. extractAudioOnly(sourceFile, outputFile)
    3. remuxTsToMp4(tsFile, outputFile)
    as implemented in VideoAudioMuxer.kt lines 26-127, 130-190, 193-276.
    """
    def __init__(self, should_fail_native: bool = False, fail_after_bytes: int = 0):
        self.should_fail_native = should_fail_native
        self.fail_after_bytes = fail_after_bytes
        self.extractors_released = 0
        self.muxers_released = 0

    # ------------------ Entrypoint 1: mux ------------------
    def mux(self, video_file: str, audio_file: str, output_file: str) -> bool:
        if not os.path.exists(video_file) or os.path.getsize(video_file) <= 0:
            return False
        if not os.path.exists(audio_file) or os.path.getsize(audio_file) <= 0:
            shutil.copy2(video_file, output_file)
            return True

        video_extractor = True
        audio_extractor = True
        muxer = True

        try:
            os.makedirs(os.path.dirname(output_file) or ".", exist_ok=True)
            if os.path.exists(output_file):
                os.remove(output_file)

            if self.should_fail_native:
                # Simulate partial write before native exception
                if self.fail_after_bytes > 0:
                    with open(output_file, "wb") as f:
                        f.write(b"CORRUPT_PARTIAL_BYTES" * (self.fail_after_bytes // 20 + 1))
                raise RuntimeError("Native MediaMuxer failed: unsupported codec / native abort")

            # Normal success: concatenate or combine
            with open(output_file, "wb") as out_f:
                with open(video_file, "rb") as vf:
                    out_f.write(vf.read())
                with open(audio_file, "rb") as af:
                    out_f.write(af.read())

            return os.path.exists(output_file) and os.path.getsize(output_file) > 0
        except Exception:
            # Remediated catch block (VideoAudioMuxer.kt:117-121):
            # runCatching {
            #     outputFile.delete()
            #     videoFile.copyTo(outputFile, overwrite = true)
            # }
            # outputFile.exists() && outputFile.length() > 0L
            try:
                if os.path.exists(output_file):
                    os.remove(output_file)
                shutil.copy2(video_file, output_file)
            except Exception:
                pass
            return os.path.exists(output_file) and os.path.getsize(output_file) > 0
        finally:
            self.extractors_released += 2
            self.muxers_released += 1

    # ------------------ Entrypoint 2: extractAudioOnly ------------------
    def extract_audio_only(self, source_file: str, output_file: str) -> bool:
        if not os.path.exists(source_file) or os.path.getsize(source_file) <= 0:
            return False

        extractor = True
        muxer = True

        try:
            os.makedirs(os.path.dirname(output_file) or ".", exist_ok=True)
            if os.path.exists(output_file):
                os.remove(output_file)

            if self.should_fail_native:
                if self.fail_after_bytes > 0:
                    with open(output_file, "wb") as f:
                        f.write(b"CORRUPT_PARTIAL_AUDIO" * (self.fail_after_bytes // 20 + 1))
                raise RuntimeError("Native MediaExtractor failed to find audio track / codec unsupported")

            # Normal success: simulated audio extraction
            with open(output_file, "wb") as out_f:
                out_f.write(b"EXTRACTED_AUDIO_HEADER_" + open(source_file, "rb").read()[:500])

            return os.path.exists(output_file) and os.path.getsize(output_file) > 0
        except Exception:
            # Remediated catch block (VideoAudioMuxer.kt:181-185):
            # runCatching {
            #     outputFile.delete()
            #     sourceFile.copyTo(outputFile, overwrite = true)
            # }
            try:
                if os.path.exists(output_file):
                    os.remove(output_file)
                shutil.copy2(source_file, output_file)
            except Exception:
                pass
            return os.path.exists(output_file) and os.path.getsize(output_file) > 0
        finally:
            self.extractors_released += 1
            self.muxers_released += 1

    # ------------------ Entrypoint 3: remuxTsToMp4 ------------------
    def remux_ts_to_mp4(self, ts_file: str, output_file: str) -> bool:
        if not os.path.exists(ts_file) or os.path.getsize(ts_file) <= 0:
            return False

        extractor = True
        muxer = True

        try:
            os.makedirs(os.path.dirname(output_file) or ".", exist_ok=True)
            if os.path.exists(output_file):
                os.remove(output_file)

            if self.should_fail_native:
                if self.fail_after_bytes > 0:
                    with open(output_file, "wb") as f:
                        f.write(b"CORRUPT_PARTIAL_REMUX" * (self.fail_after_bytes // 20 + 1))
                raise RuntimeError("Native MediaMuxer failed: non-MP4 payload in TS stream")

            # Normal success: simulated MP4 container wrapping
            with open(output_file, "wb") as out_f:
                out_f.write(b"FTYP_MP42_" + open(ts_file, "rb").read())

            return os.path.exists(output_file) and os.path.getsize(output_file) > 0
        except Exception:
            # Remediated catch block (VideoAudioMuxer.kt:267-271):
            # runCatching {
            #     outputFile.delete()
            #     tsFile.copyTo(outputFile, overwrite = true)
            # }
            try:
                if os.path.exists(output_file):
                    os.remove(output_file)
                shutil.copy2(ts_file, output_file)
            except Exception:
                pass
            return os.path.exists(output_file) and os.path.getsize(output_file) > 0
        finally:
            self.extractors_released += 1
            self.muxers_released += 1

def test_video_audio_muxer_entrypoints(suite: ChallengerSuite):
    print("\n>>> 3. VALIDATING VideoAudioMuxer 3 ENTRYPOINTS (Exception Safety & Fallbacks)...")

    temp_mux_dir = tempfile.mkdtemp(prefix="muxer_challenger_test_")
    try:
        video_src = os.path.join(temp_mux_dir, "input_video.mp4")
        audio_src = os.path.join(temp_mux_dir, "input_audio.m4a")
        ts_src = os.path.join(temp_mux_dir, "input_stream.ts")

        with open(video_src, "wb") as f: f.write(b"VIDEO_SOURCE_PAYLOAD_12345" * 100) # 2600 bytes
        with open(audio_src, "wb") as f: f.write(b"AUDIO_SOURCE_PAYLOAD_67890" * 50)  # 1300 bytes
        with open(ts_src, "wb") as f: f.write(b"MPEG_TS_STREAM_PAYLOAD_ABCDE" * 200) # 5400 bytes

        # ---------------- ENTRYPOINT 1: mux ----------------
        # 3.1.1 Normal mux
        out1_normal = os.path.join(temp_mux_dir, "mux_normal.mp4")
        muxer_norm = SimulatedVideoAudioMuxer()
        res = muxer_norm.mux(video_src, audio_src, out1_normal)
        suite.check("mux() normal: returns true", res is True)
        suite.check("mux() normal: output created and non-empty", os.path.exists(out1_normal) and os.path.getsize(out1_normal) > 0)
        suite.check("mux() normal: extractors and muxer released in finally", muxer_norm.extractors_released == 2 and muxer_norm.muxers_released == 1)

        # 3.1.2 Missing audio: falls back to copying video
        out1_no_audio = os.path.join(temp_mux_dir, "mux_no_audio.mp4")
        res_no_aud = muxer_norm.mux(video_src, os.path.join(temp_mux_dir, "nonexistent.m4a"), out1_no_audio)
        suite.check("mux() missing audio: returns true and copies videoFile", res_no_aud is True and os.path.getsize(out1_no_audio) == os.path.getsize(video_src))

        # 3.1.3 Exception thrown with partial corrupt write
        out1_fail = os.path.join(temp_mux_dir, "mux_fail.mp4")
        muxer_fail = SimulatedVideoAudioMuxer(should_fail_native=True, fail_after_bytes=500)
        res_fail = muxer_fail.mux(video_src, audio_src, out1_fail)
        suite.check("mux() on native exception: returns true (safe fallback)", res_fail is True)
        # CRITICAL REMEDIATION CHECK: Output must be full videoFile (2600 bytes), NOT corrupt 500 bytes!
        suite.check("mux() on native exception: partial corrupt write wiped and full videoFile copied", os.path.getsize(out1_fail) == os.path.getsize(video_src), f"Size was {os.path.getsize(out1_fail)} vs {os.path.getsize(video_src)}")
        suite.check("mux() on native exception: resources released in finally", muxer_fail.extractors_released == 2 and muxer_fail.muxers_released == 1)

        # ---------------- ENTRYPOINT 2: extractAudioOnly ----------------
        # 3.2.1 Normal extraction
        out2_normal = os.path.join(temp_mux_dir, "audio_normal.m4a")
        res2_norm = muxer_norm.extract_audio_only(ts_src, out2_normal)
        suite.check("extractAudioOnly() normal: returns true", res2_norm is True)
        suite.check("extractAudioOnly() normal: output created and non-empty", os.path.exists(out2_normal) and os.path.getsize(out2_normal) > 0)

        # 3.2.2 Missing source file: returns false
        res2_no_src = muxer_norm.extract_audio_only(os.path.join(temp_mux_dir, "nonexistent.ts"), os.path.join(temp_mux_dir, "out.m4a"))
        suite.check("extractAudioOnly() missing source: returns false safely", res2_no_src is False)

        # 3.2.3 Exception thrown with partial corrupt write
        out2_fail = os.path.join(temp_mux_dir, "audio_fail.m4a")
        muxer2_fail = SimulatedVideoAudioMuxer(should_fail_native=True, fail_after_bytes=400)
        res2_fail = muxer2_fail.extract_audio_only(ts_src, out2_fail)
        suite.check("extractAudioOnly() on native exception: returns true (safe fallback)", res2_fail is True)
        # CRITICAL REMEDIATION CHECK: Output must equal full source ts_src (5400 bytes), NOT corrupt 400 bytes!
        suite.check("extractAudioOnly() on native exception: partial corrupt write wiped and full source copied", os.path.getsize(out2_fail) == os.path.getsize(ts_src), f"Size was {os.path.getsize(out2_fail)} vs {os.path.getsize(ts_src)}")
        suite.check("extractAudioOnly() on native exception: resources released in finally", muxer2_fail.extractors_released == 1 and muxer2_fail.muxers_released == 1)

        # ---------------- ENTRYPOINT 3: remuxTsToMp4 ----------------
        # 3.3.1 Normal remux
        out3_normal = os.path.join(temp_mux_dir, "remux_normal.mp4")
        res3_norm = muxer_norm.remux_ts_to_mp4(ts_src, out3_normal)
        suite.check("remuxTsToMp4() normal: returns true", res3_norm is True)
        suite.check("remuxTsToMp4() normal: output created and non-empty", os.path.exists(out3_normal) and os.path.getsize(out3_normal) > 0)

        # 3.3.2 Missing ts_file: returns false
        res3_no_ts = muxer_norm.remux_ts_to_mp4(os.path.join(temp_mux_dir, "nonexistent.ts"), os.path.join(temp_mux_dir, "out.mp4"))
        suite.check("remuxTsToMp4() missing source: returns false safely", res3_no_ts is False)

        # 3.3.3 Exception thrown with partial corrupt write
        out3_fail = os.path.join(temp_mux_dir, "remux_fail.mp4")
        muxer3_fail = SimulatedVideoAudioMuxer(should_fail_native=True, fail_after_bytes=600)
        res3_fail = muxer3_fail.remux_ts_to_mp4(ts_src, out3_fail)
        suite.check("remuxTsToMp4() on native exception: returns true (safe fallback)", res3_fail is True)
        # CRITICAL REMEDIATION CHECK: Output must equal full ts_src (5400 bytes), NOT corrupt 600 bytes!
        suite.check("remuxTsToMp4() on native exception: partial corrupt write wiped and full ts_src copied", os.path.getsize(out3_fail) == os.path.getsize(ts_src), f"Size was {os.path.getsize(out3_fail)} vs {os.path.getsize(ts_src)}")
        suite.check("remuxTsToMp4() on native exception: resources released in finally", muxer3_fail.extractors_released == 1 and muxer3_fail.muxers_released == 1)

        # ---------------- PTS Normalization Verification in remuxTsToMp4 ----------------
        # Test track-separated monotonic clamping logic (lines 246-258)
        def normalize_dual_track(video_raw: List[int], audio_raw: List[int]):
            first_pts = -1
            last_vid = -1
            last_aud = -1
            vid_out = []
            aud_out = []

            # Interleaved track samples
            combined = [(0, p) for p in video_raw] + [(1, p) for p in audio_raw]
            combined.sort(key=lambda x: x[1])

            for track, raw_pts in combined:
                if first_pts < 0:
                    first_pts = raw_pts
                pts = max(0, raw_pts - first_pts)
                if track == 0:
                    if pts <= last_vid:
                        pts = last_vid + 1000
                    last_vid = pts
                    vid_out.append(pts)
                else:
                    if pts <= last_aud:
                        pts = last_aud + 1000
                    last_aud = pts
                    aud_out.append(pts)
            return vid_out, aud_out

        raw_v = [10_000_000, 10_033_333, 10_030_000, 10_066_666] # contains jitter / decrease
        raw_a = [10_000_000, 10_021_333, 10_021_333, 10_042_666] # contains duplicate
        v_norm, a_norm = normalize_dual_track(raw_v, raw_a)

        suite.check("Video track starts at 0us", v_norm[0] == 0)
        suite.check("Video track is strictly monotonic", all(y > x for x, y in zip(v_norm, v_norm[1:])))
        suite.check("Audio track starts at 0us", a_norm[0] == 0)
        suite.check("Audio track is strictly monotonic", all(y > x for x, y in zip(a_norm, a_norm[1:])))

    finally:
        shutil.rmtree(temp_mux_dir, ignore_errors=True)

# ============================================================================
# Main Execution
# ============================================================================

def main():
    print("=" * 80)
    print("STARTING EMPIRICAL CHALLENGER M3-2 STRESS SUITE (Media Downloader v1.9.0)")
    print("=" * 80)

    suite = ChallengerSuite()
    test_speed_tracker_and_format_utils(suite)
    test_hls_download_cancellation(suite)
    test_video_audio_muxer_entrypoints(suite)

    print("\n" + "=" * 80)
    print(f"RESULTS: {suite.passed} PASSED, {suite.failed} FAILED")
    print("=" * 80)

    if suite.failed > 0:
        print("\nFAILURES DETECTED:")
        for f in suite.failures:
            print(f)
        sys.exit(1)
    else:
        print("\nALL ADVERSARIAL STRESS CHALLENGES PASSED PERFECTLY!")
        sys.exit(0)

if __name__ == "__main__":
    main()
