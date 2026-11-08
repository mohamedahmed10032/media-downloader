#!/usr/bin/env python3
"""
Stand-alone Empirical Challenge Harness for Media Downloader Storage Layer:
- MimeTypes.kt (MimeTypes.forContainer)
- MediaStoreWriter.kt (path logic, ContentValues, fallback chain, error recovery)

Author: Challenger Agent 1 (critic / specialist)
Date: 2026-11-08
"""

import sys
import os
import time
from enum import Enum
from typing import Dict, Any, Optional, List

class MediaType(Enum):
    VIDEO = "VIDEO"
    AUDIO = "AUDIO"
    IMAGE = "IMAGE"

# ---------------------------------------------------------------------------
# 1. Faithfully model MimeTypes.kt
# ---------------------------------------------------------------------------
class MimeTypesModel:
    @staticmethod
    def for_container(container: str, media_type: MediaType, simulated_mime_type_map: Optional[Dict[str, str]] = None) -> str:
        # Kotlin: val ext = container.lowercase().trimStart('.')
        ext = container.lower().lstrip('.')
        mime_map = simulated_mime_type_map or {}

        if media_type == MediaType.AUDIO:
            if ext == "mp3":
                return "audio/mpeg"
            elif ext == "m4a":
                return "audio/mp4"
            elif ext == "aac":
                return "audio/aac"
            elif ext == "wav":
                return "audio/wav"
            elif ext == "flac":
                return "audio/flac"
            elif ext == "ogg":
                return "audio/ogg"
            elif ext == "opus":
                return "audio/opus"
            elif ext == "webm":
                return "audio/webm"
            elif ext == "mp4":
                return "audio/mp4"
            elif ext in ("3gp", "3gpp"):
                return "audio/3gpp"
            elif ext == "mka":
                return "audio/x-matroska"
            elif ext in ("mid", "midi"):
                return "audio/midi"
            elif ext == "wma":
                return "audio/x-ms-wma"
            else:
                mapped = mime_map.get(ext)
                if mapped and mapped.startswith("audio/"):
                    return mapped
                return "audio/mpeg"

        elif media_type == MediaType.VIDEO:
            if ext in ("mp4", "m4v"):
                return "video/mp4"
            elif ext == "webm":
                return "video/webm"
            elif ext == "mkv":
                return "video/x-matroska"
            elif ext == "mov":
                return "video/quicktime"
            elif ext in ("3gp", "3gpp"):
                return "video/3gpp"
            elif ext == "ts":
                return "video/mp2t"
            elif ext == "flv":
                return "video/x-flv"
            elif ext == "avi":
                return "video/x-msvideo"
            elif ext == "wmv":
                return "video/x-ms-wmv"
            elif ext == "asf":
                return "video/x-ms-asf"
            else:
                mapped = mime_map.get(ext)
                if mapped and mapped.startswith("video/"):
                    return mapped
                return "video/mp4"

        elif media_type == MediaType.IMAGE:
            if ext in ("jpg", "jpeg"):
                return "image/jpeg"
            elif ext == "png":
                return "image/png"
            elif ext == "gif":
                return "image/gif"
            elif ext == "webp":
                return "image/webp"
            elif ext == "bmp":
                return "image/bmp"
            elif ext in ("heic", "heif"):
                return "image/heic"
            elif ext == "svg":
                return "image/svg+xml"
            elif ext == "ico":
                return "image/x-icon"
            else:
                mapped = mime_map.get(ext)
                if mapped and mapped.startswith("image/"):
                    return mapped
                return "image/jpeg"

        raise ValueError(f"Unknown media type {media_type}")

# ---------------------------------------------------------------------------
# 2. Faithfully model MediaStoreWriter.kt
# ---------------------------------------------------------------------------
class MockMediaStore:
    VOLUME_EXTERNAL_PRIMARY = "external_primary"
    DIRECTORY_MOVIES = "Movies"
    DIRECTORY_MUSIC = "Music"
    DIRECTORY_PICTURES = "Pictures"
    DIRECTORY_DOWNLOADS = "Download"

    DISPLAY_NAME = "_display_name"
    TITLE = "title"
    MIME_TYPE = "mime_type"
    DATE_ADDED = "date_added"
    IS_MUSIC = "is_music"
    RELATIVE_PATH = "relative_path"
    IS_PENDING = "is_pending"
    DATA = "_data"

class MockContentResolver:
    def __init__(self):
        self.inserts: List[Dict[str, Any]] = []
        self.updates: List[Dict[str, Any]] = []
        self.deletes: List[Dict[str, Any]] = []
        self.streams_opened: List[str] = []
        
        # Injected behaviors for stress-testing
        self.fail_insert_on_primary = False
        self.fail_insert_on_external = False
        self.fail_insert_all_media = False
        self.fail_open_stream = False
        self.fail_write_stream = False
        self.fail_update_is_pending = False
        self.simulate_rom_security_exception_on_update = False

    def insert(self, target_uri: str, values: Dict[str, Any]) -> Optional[str]:
        if "external_primary" in target_uri and self.fail_insert_on_primary:
            return None
        if "external" in target_uri and "primary" not in target_uri and self.fail_insert_on_external:
            return None
        if self.fail_insert_all_media and "download" not in target_uri.lower():
            return None
            
        uri = f"{target_uri}/{len(self.inserts) + 1001}"
        self.inserts.append({"uri": uri, "target": target_uri, "values": dict(values)})
        return uri

    def open_output_stream(self, uri: str):
        if self.fail_open_stream:
            return None
        self.streams_opened.append(uri)
        fail_write = self.fail_write_stream

        class MockStream:
            def __enter__(self):
                return self
            def __exit__(self, exc_type, exc_val, exc_tb):
                pass
            def write(self, data):
                if fail_write:
                    raise IOError("write failed: ENOSPC (No space left on device)")
        return MockStream()

    def update(self, uri: str, values: Dict[str, Any], where=None, args=None) -> int:
        if self.fail_update_is_pending or self.simulate_rom_security_exception_on_update:
            raise RuntimeError("SecurityException: Modification not allowed by OEM package manager")
        self.updates.append({"uri": uri, "values": dict(values)})
        return 1

    def delete(self, uri: str, where=None, args=None) -> int:
        self.deletes.append({"uri": uri})
        return 1

class MediaStoreWriterModel:
    def __init__(self, resolver: MockContentResolver, sdk_int: int = 33):
        self.resolver = resolver
        self.sdk_int = sdk_int

    def base_directory(self, media_type: MediaType) -> str:
        if media_type == MediaType.VIDEO:
            return MockMediaStore.DIRECTORY_MOVIES
        elif media_type == MediaType.AUDIO:
            return MockMediaStore.DIRECTORY_MUSIC
        elif media_type == MediaType.IMAGE:
            return MockMediaStore.DIRECTORY_PICTURES
        raise ValueError()

    def compute_relative_path(self, media_type: MediaType, subfolder: str) -> str:
        directory = self.base_directory(media_type)
        # Kotlin: val cleanSub = subfolder.trim().trim('/')
        # String.trim().trim('/') in Kotlin strips whitespace then leading/trailing slashes
        clean_sub = subfolder.strip().strip('/')
        if len(clean_sub) == 0:
            return f"{directory}/"
        else:
            return f"{directory}/{clean_sub}/"

    def compute_title(self, display_name: str) -> str:
        # Kotlin: val title = displayName.substringBeforeLast('.', displayName)
        if '.' in display_name:
            return display_name.rsplit('.', 1)[0]
        return display_name

    def create_values(self, name: str, dest_dir: str, rel_path: str,
                      display_name: str, media_type: MediaType, mime: str, subfolder: str) -> Dict[str, Any]:
        title = self.compute_title(display_name)
        clean_sub = subfolder.strip().strip('/')
        date_added = int(time.time())

        values = {
            MockMediaStore.DISPLAY_NAME: name,
            MockMediaStore.TITLE: title,
            MockMediaStore.MIME_TYPE: mime,
            MockMediaStore.DATE_ADDED: date_added
        }
        if media_type == MediaType.AUDIO:
            values[MockMediaStore.IS_MUSIC] = 1

        if self.sdk_int >= 29:  # Build.VERSION_CODES.Q
            values[MockMediaStore.RELATIVE_PATH] = rel_path
            values[MockMediaStore.IS_PENDING] = 1
        else:
            if len(clean_sub) == 0:
                parent_dir = f"/storage/emulated/0/{dest_dir}"
            else:
                parent_dir = f"/storage/emulated/0/{dest_dir}/{clean_sub}"
            values[MockMediaStore.DATA] = f"{parent_dir}/{name}"

        return values

    def try_insert(self, values: Dict[str, Any], media_type: MediaType) -> Optional[str]:
        q = self.sdk_int >= 29
        type_str = media_type.value.lower()
        if q:
            targets = [
                f"content://media/{MockMediaStore.VOLUME_EXTERNAL_PRIMARY}/{type_str}",
                f"content://media/external/{type_str}"
            ]
        else:
            targets = [f"content://media/external/{type_str}"]

        for target in targets:
            try:
                res = self.resolver.insert(target, values)
                if res is not None:
                    return res
            except Exception:
                pass
        return None

    def publish(self, staging_exists: bool, staging_size: int,
                display_name: str, media_type: MediaType,
                container: str, subfolder: str) -> str:
        if not staging_exists or staging_size <= 0:
            raise RuntimeError("MediaException: Staging file missing or empty")

        mime = MimeTypesModel.for_container(container, media_type)
        directory = self.base_directory(media_type)
        clean_sub = subfolder.strip().strip('/')
        rel_path = self.compute_relative_path(media_type, subfolder)
        title = self.compute_title(display_name)

        # Primary insert
        item_uri = self.try_insert(
            self.create_values(display_name, directory, rel_path, display_name, media_type, mime, subfolder),
            media_type
        )

        # Unique name fallback
        if item_uri is None:
            if '.' in display_name:
                base, ext = display_name.rsplit('.', 1)
                unique_name = f"{base} ({int(time.time() * 1000) % 10000}).{ext}"
            else:
                unique_name = f"{display_name} ({int(time.time() * 1000) % 10000})"
            item_uri = self.try_insert(
                self.create_values(unique_name, directory, rel_path, display_name, media_type, mime, subfolder),
                media_type
            )

        # Scoped storage Downloads fallback
        if item_uri is None and self.sdk_int >= 29:
            dl_dir = MockMediaStore.DIRECTORY_DOWNLOADS
            dl_rel = f"{dl_dir}/" if len(clean_sub) == 0 else f"{dl_dir}/{clean_sub}/"
            dl_values = self.create_values(display_name, dl_dir, dl_rel, display_name, media_type, mime, subfolder)
            
            try:
                res = self.resolver.insert(f"content://media/{MockMediaStore.VOLUME_EXTERNAL_PRIMARY}/downloads", dl_values)
                if res is None:
                    res = self.resolver.insert("content://media/external/downloads", dl_values)
                item_uri = res
            except Exception:
                item_uri = None

        if item_uri is None:
            raise RuntimeError("MediaException: MediaStore insert failed")

        staging_deleted = False
        try:
            stream = self.resolver.open_output_stream(item_uri)
            if stream is None:
                raise RuntimeError("MediaException: Unable to open output stream")
            with stream:
                stream.write(b"data" * 100)

            if self.sdk_int >= 29:
                # Kotlin: runCatching { resolver.update(itemUri, done, null, null) }
                try:
                    done_values = {MockMediaStore.IS_PENDING: 0}
                    self.resolver.update(item_uri, done_values)
                except Exception:
                    # Caught by runCatching: file is NOT deleted!
                    pass
        except Exception as e:
            # Delete on failure
            try:
                self.resolver.delete(item_uri)
            except Exception:
                pass
            raise RuntimeError(f"MediaException: {e}")

        staging_deleted = True
        return item_uri

# ---------------------------------------------------------------------------
# Test Runner & Assertions
# ---------------------------------------------------------------------------
def run_all_tests():
    print("=" * 70)
    print("STARTING EMPIRICAL TEST SUITE: Media Downloader Storage Layer")
    print("=" * 70)

    total_tests = 0
    passed_tests = 0

    def assert_eq(actual, expected, test_name):
        nonlocal total_tests, passed_tests
        total_tests += 1
        if actual == expected:
            passed_tests += 1
            print(f"  [PASS] {test_name}: got {actual!r}")
        else:
            print(f"  [FAIL] {test_name}: expected {expected!r}, got {actual!r}")
            raise AssertionError(f"Test failed: {test_name}")

    def assert_true(cond, test_name):
        nonlocal total_tests, passed_tests
        total_tests += 1
        if cond:
            passed_tests += 1
            print(f"  [PASS] {test_name}")
        else:
            print(f"  [FAIL] {test_name}")
            raise AssertionError(f"Test failed: {test_name}")

    # =========================================================================
    # PART 1: MimeTypes.forContainer edge cases
    # =========================================================================
    print("\n--- Part 1: MimeTypes.forContainer edge cases ---")

    # 1.1 Uppercase extensions
    assert_eq(MimeTypesModel.for_container("WEBM", MediaType.AUDIO), "audio/webm", "Uppercase WEBM audio")
    assert_eq(MimeTypesModel.for_container("WEBM", MediaType.VIDEO), "video/webm", "Uppercase WEBM video")
    assert_eq(MimeTypesModel.for_container("M4A", MediaType.AUDIO), "audio/mp4", "Uppercase M4A audio")
    assert_eq(MimeTypesModel.for_container("MP4", MediaType.AUDIO), "audio/mp4", "Uppercase MP4 audio")
    assert_eq(MimeTypesModel.for_container("MP4", MediaType.VIDEO), "video/mp4", "Uppercase MP4 video")
    assert_eq(MimeTypesModel.for_container("MP3", MediaType.AUDIO), "audio/mpeg", "Uppercase MP3 audio")
    assert_eq(MimeTypesModel.for_container("FLAC", MediaType.AUDIO), "audio/flac", "Uppercase FLAC audio")
    assert_eq(MimeTypesModel.for_container("WAV", MediaType.AUDIO), "audio/wav", "Uppercase WAV audio")
    assert_eq(MimeTypesModel.for_container("OPUS", MediaType.AUDIO), "audio/opus", "Uppercase OPUS audio")
    assert_eq(MimeTypesModel.for_container("JPG", MediaType.IMAGE), "image/jpeg", "Uppercase JPG image")
    assert_eq(MimeTypesModel.for_container("PNG", MediaType.IMAGE), "image/png", "Uppercase PNG image")

    # 1.2 Leading dots
    assert_eq(MimeTypesModel.for_container(".mp3", MediaType.AUDIO), "audio/mpeg", "Leading dot .mp3")
    assert_eq(MimeTypesModel.for_container(".m4a", MediaType.AUDIO), "audio/mp4", "Leading dot .m4a")
    assert_eq(MimeTypesModel.for_container(".webm", MediaType.AUDIO), "audio/webm", "Leading dot .webm audio")
    assert_eq(MimeTypesModel.for_container("..mp3", MediaType.AUDIO), "audio/mpeg", "Double leading dot ..mp3")
    assert_eq(MimeTypesModel.for_container("...WEBM", MediaType.AUDIO), "audio/webm", "Triple leading dot ...WEBM")
    assert_eq(MimeTypesModel.for_container(".jpg", MediaType.IMAGE), "image/jpeg", "Leading dot .jpg")

    # 1.3 Dual-use containers (CRUCIAL: AUDIO must start with audio/)
    dual_containers = ["webm", "mp4", "3gp", "3gpp", "mka"]
    for c in dual_containers:
        audio_mime = MimeTypesModel.for_container(c, MediaType.AUDIO)
        assert_true(audio_mime.startswith("audio/"), f"Dual-use container '{c}' with AUDIO must return audio/* MIME: {audio_mime}")
        
        video_mime = MimeTypesModel.for_container(c, MediaType.VIDEO)
        assert_true(video_mime.startswith("video/"), f"Dual-use container '{c}' with VIDEO must return video/* MIME: {video_mime}")

    # Specific dual-use exact checks
    assert_eq(MimeTypesModel.for_container("webm", MediaType.AUDIO), "audio/webm", "Dual webm -> audio/webm")
    assert_eq(MimeTypesModel.for_container("webm", MediaType.VIDEO), "video/webm", "Dual webm -> video/webm")
    assert_eq(MimeTypesModel.for_container("mp4", MediaType.AUDIO), "audio/mp4", "Dual mp4 -> audio/mp4")
    assert_eq(MimeTypesModel.for_container("mp4", MediaType.VIDEO), "video/mp4", "Dual mp4 -> video/mp4")
    assert_eq(MimeTypesModel.for_container("3gp", MediaType.AUDIO), "audio/3gpp", "Dual 3gp -> audio/3gpp")
    assert_eq(MimeTypesModel.for_container("3gp", MediaType.VIDEO), "video/3gpp", "Dual 3gp -> video/3gpp")
    assert_eq(MimeTypesModel.for_container("mka", MediaType.AUDIO), "audio/x-matroska", "mka -> audio/x-matroska")

    # Additional audio formats
    assert_eq(MimeTypesModel.for_container("mid", MediaType.AUDIO), "audio/midi", "mid audio")
    assert_eq(MimeTypesModel.for_container("midi", MediaType.AUDIO), "audio/midi", "midi audio")
    assert_eq(MimeTypesModel.for_container("wma", MediaType.AUDIO), "audio/x-ms-wma", "wma audio")

    # 1.4 Unknown containers
    assert_eq(MimeTypesModel.for_container("xyz", MediaType.AUDIO), "audio/mpeg", "Unknown audio -> audio/mpeg")
    assert_eq(MimeTypesModel.for_container("unknown_format", MediaType.AUDIO), "audio/mpeg", "Unknown format audio")
    assert_eq(MimeTypesModel.for_container("xyz", MediaType.VIDEO), "video/mp4", "Unknown video -> video/mp4")
    assert_eq(MimeTypesModel.for_container("xyz", MediaType.IMAGE), "image/jpeg", "Unknown image -> image/jpeg")

    # 1.5 Empty strings, dots-only, whitespace
    assert_eq(MimeTypesModel.for_container("", MediaType.AUDIO), "audio/mpeg", "Empty container audio")
    assert_eq(MimeTypesModel.for_container("", MediaType.VIDEO), "video/mp4", "Empty container video")
    assert_eq(MimeTypesModel.for_container("", MediaType.IMAGE), "image/jpeg", "Empty container image")
    assert_eq(MimeTypesModel.for_container("...", MediaType.AUDIO), "audio/mpeg", "Dots-only container audio")
    assert_eq(MimeTypesModel.for_container("   ", MediaType.AUDIO), "audio/mpeg", "Whitespace container audio")

    # =========================================================================
    # PART 2: MediaStoreWriter path logic
    # =========================================================================
    print("\n--- Part 2: MediaStoreWriter path logic ---")
    mock_res = MockContentResolver()
    writer = MediaStoreWriterModel(mock_res, sdk_int=33)

    # 2.1 Leading slash
    path1 = writer.compute_relative_path(MediaType.AUDIO, "/MediaDownloader")
    assert_eq(path1, "Music/MediaDownloader/", "Leading slash /MediaDownloader")

    # 2.2 Trailing slash
    path2 = writer.compute_relative_path(MediaType.AUDIO, "MediaDownloader/")
    assert_eq(path2, "Music/MediaDownloader/", "Trailing slash MediaDownloader/")

    # 2.3 Both leading and trailing slashes
    path3 = writer.compute_relative_path(MediaType.AUDIO, "/MediaDownloader/")
    assert_eq(path3, "Music/MediaDownloader/", "Both slashes /MediaDownloader/")

    # 2.4 Multiple leading and trailing slashes
    path4 = writer.compute_relative_path(MediaType.AUDIO, "///MediaDownloader///")
    assert_eq(path4, "Music/MediaDownloader/", "Multiple slashes ///MediaDownloader///")

    # 2.5 Empty subfolder
    path5 = writer.compute_relative_path(MediaType.AUDIO, "")
    assert_eq(path5, "Music/", "Empty subfolder audio")
    path5_vid = writer.compute_relative_path(MediaType.VIDEO, "")
    assert_eq(path5_vid, "Movies/", "Empty subfolder video")
    path5_img = writer.compute_relative_path(MediaType.IMAGE, "")
    assert_eq(path5_img, "Pictures/", "Empty subfolder image")

    # 2.6 Whitespace subfolder
    path6 = writer.compute_relative_path(MediaType.AUDIO, "   ")
    assert_eq(path6, "Music/", "Whitespace subfolder audio")
    path6_tabs = writer.compute_relative_path(MediaType.AUDIO, " \t\n/MediaDownloader/ \t")
    assert_eq(path6_tabs, "Music/MediaDownloader/", "Whitespace and slashes")

    # 2.7 Nested subfolder
    path7 = writer.compute_relative_path(MediaType.AUDIO, "/MediaDownloader/Audio/Podcasts/")
    assert_eq(path7, "Music/MediaDownloader/Audio/Podcasts/", "Nested subfolder")

    # Crucial property: Scoped Storage relative path MUST end with trailing slash and NOT start with slash
    for p in [path1, path2, path3, path4, path5, path6, path7]:
        assert_true(p.endswith("/"), f"Path '{p}' must end with trailing slash")
        assert_true(not p.startswith("/"), f"Path '{p}' must not start with slash")

    # =========================================================================
    # PART 3: MediaStoreWriter ContentValues
    # =========================================================================
    print("\n--- Part 3: MediaStoreWriter ContentValues ---")

    # 3.1 IS_MUSIC only for AUDIO
    vals_audio = writer.create_values("track.mp3", "Music", "Music/", "track.mp3", MediaType.AUDIO, "audio/mpeg", "")
    assert_true(MockMediaStore.IS_MUSIC in vals_audio, "IS_MUSIC present in AUDIO ContentValues")
    assert_eq(vals_audio[MockMediaStore.IS_MUSIC], 1, "IS_MUSIC == 1 for AUDIO")

    vals_video = writer.create_values("clip.mp4", "Movies", "Movies/", "clip.mp4", MediaType.VIDEO, "video/mp4", "")
    assert_true(MockMediaStore.IS_MUSIC not in vals_video, "IS_MUSIC absent in VIDEO ContentValues")

    vals_image = writer.create_values("photo.jpg", "Pictures", "Pictures/", "photo.jpg", MediaType.IMAGE, "image/jpeg", "")
    assert_true(MockMediaStore.IS_MUSIC not in vals_image, "IS_MUSIC absent in IMAGE ContentValues")

    # 3.2 TITLE strips extension
    title1 = writer.compute_title("Imagine Dragons - Bones.mp3")
    assert_eq(title1, "Imagine Dragons - Bones", "TITLE strips single extension")

    title2 = writer.compute_title("Imagine Dragons - Bones (Official).webm")
    assert_eq(title2, "Imagine Dragons - Bones (Official)", "TITLE strips webm extension")

    title3 = writer.compute_title("track.v1.2.mp3")
    assert_eq(title3, "track.v1.2", "TITLE strips only last extension")

    title4 = writer.compute_title("song_without_extension")
    assert_eq(title4, "song_without_extension", "TITLE preserves name when no extension")

    # Verify TITLE inside create_values captures clean title even when unique name is generated
    vals_unique = writer.create_values("song (1234).mp3", "Music", "Music/", "song.mp3", MediaType.AUDIO, "audio/mpeg", "")
    assert_eq(vals_unique[MockMediaStore.DISPLAY_NAME], "song (1234).mp3", "DISPLAY_NAME has unique suffix")
    assert_eq(vals_unique[MockMediaStore.TITLE], "song", "TITLE is pristine without unique suffix")

    # 3.3 DATE_ADDED is valid epoch seconds
    now_sec = int(time.time())
    date_added = vals_audio[MockMediaStore.DATE_ADDED]
    assert_true(isinstance(date_added, int), "DATE_ADDED must be integer")
    assert_true(abs(date_added - now_sec) <= 2, f"DATE_ADDED {date_added} must be current epoch seconds (expected ~{now_sec})")
    assert_true(date_added < 2_000_000_000, "DATE_ADDED must be in seconds, NOT milliseconds (< 2e9)")

    # =========================================================================
    # PART 4: Error paths and IS_PENDING resilience
    # =========================================================================
    print("\n--- Part 4: Error paths and IS_PENDING resilience ---")

    # 4.1 Success path
    res_normal = MockContentResolver()
    writer_normal = MediaStoreWriterModel(res_normal, sdk_int=33)
    uri_success = writer_normal.publish(
        staging_exists=True, staging_size=1024,
        display_name="test_song.mp3", media_type=MediaType.AUDIO,
        container="mp3", subfolder="MediaDownloader"
    )
    assert_true(uri_success.startswith("content://media/"), "publish returns valid content URI")
    assert_eq(len(res_normal.inserts), 1, "Resolver has 1 insert")
    assert_eq(len(res_normal.updates), 1, "Resolver has 1 update (IS_PENDING=0)")
    assert_eq(res_normal.updates[0]["values"][MockMediaStore.IS_PENDING], 0, "IS_PENDING updated to 0")
    assert_eq(len(res_normal.deletes), 0, "No deletes on success")

    # 4.2 CRITICAL ADVERSARIAL STRESS TEST: IS_PENDING update failure
    # If OEM ROM or framework throws SecurityException or DeadObjectException when updating IS_PENDING=0,
    # the runCatching block MUST catch it and PREVENT deleting the written file!
    res_rom_bug = MockContentResolver()
    res_rom_bug.simulate_rom_security_exception_on_update = True
    writer_rom_bug = MediaStoreWriterModel(res_rom_bug, sdk_int=33)
    
    uri_rom_bug = writer_rom_bug.publish(
        staging_exists=True, staging_size=1024,
        display_name="test_song_rom.mp3", media_type=MediaType.AUDIO,
        container="mp3", subfolder="MediaDownloader"
    )
    assert_true(uri_rom_bug is not None, "publish completes despite IS_PENDING update exception")
    assert_eq(len(res_rom_bug.deletes), 0, "CRITICAL: Successfully written file was NOT deleted after IS_PENDING update error!")
    assert_true("test_song_rom.mp3" in res_rom_bug.inserts[0]["values"][MockMediaStore.DISPLAY_NAME], "File remains in MediaStore")

    # 4.3 Error path: IO error during stream write (e.g. disk full)
    # The file is incomplete -> resolver.delete MUST be called to clean up corrupt entry!
    res_disk_full = MockContentResolver()
    res_disk_full.fail_write_stream = True
    writer_disk_full = MediaStoreWriterModel(res_disk_full, sdk_int=33)
    
    io_exception_caught = False
    try:
        writer_disk_full.publish(
            staging_exists=True, staging_size=1024,
            display_name="corrupt.mp3", media_type=MediaType.AUDIO,
            container="mp3", subfolder="MediaDownloader"
        )
    except Exception as e:
        io_exception_caught = True
        assert_true("MediaException" in str(e), "MediaException thrown on write failure")
    
    assert_true(io_exception_caught, "IOException triggered MediaException")
    assert_eq(len(res_disk_full.deletes), 1, "Corrupted/incomplete file WAS cleaned up via resolver.delete")

    # 4.4 Error path: Open stream failure
    res_open_fail = MockContentResolver()
    res_open_fail.fail_open_stream = True
    writer_open_fail = MediaStoreWriterModel(res_open_fail, sdk_int=33)
    
    open_err_caught = False
    try:
        writer_open_fail.publish(
            staging_exists=True, staging_size=1024,
            display_name="no_stream.mp3", media_type=MediaType.AUDIO,
            container="mp3", subfolder="MediaDownloader"
        )
    except Exception as e:
        open_err_caught = True
        assert_true("Unable to open output stream" in str(e), "Proper error message when stream is null")
    assert_true(open_err_caught, "Null stream triggered exception")
    assert_eq(len(res_open_fail.deletes), 1, "Empty placeholder row was cleaned up")

    # 4.5 Error path: Missing or 0-byte staging file
    res_empty_file = MockContentResolver()
    writer_empty_file = MediaStoreWriterModel(res_empty_file, sdk_int=33)
    empty_err = False
    try:
        writer_empty_file.publish(
            staging_exists=False, staging_size=0,
            display_name="ghost.mp3", media_type=MediaType.AUDIO,
            container="mp3", subfolder="MediaDownloader"
        )
    except Exception as e:
        empty_err = True
        assert_true("Staging file missing or empty" in str(e), "Rejects missing staging file")
    assert_true(empty_err, "Missing file correctly rejected")
    assert_eq(len(res_empty_file.inserts), 0, "No MediaStore row created for missing file")

    # 4.6 Fallback chain: Primary fails -> external succeeds
    res_fb1 = MockContentResolver()
    res_fb1.fail_insert_on_primary = True
    writer_fb1 = MediaStoreWriterModel(res_fb1, sdk_int=33)
    uri_fb1 = writer_fb1.publish(
        staging_exists=True, staging_size=1024,
        display_name="fallback1.mp3", media_type=MediaType.AUDIO,
        container="mp3", subfolder="MediaDownloader"
    )
    assert_true("external/audio" in res_fb1.inserts[0]["target"], "Fell back to external content URI")

    # 4.7 Fallback chain: Primary and external fail -> Downloads collection fallback
    res_fb2 = MockContentResolver()
    res_fb2.fail_insert_all_media = True
    writer_fb2 = MediaStoreWriterModel(res_fb2, sdk_int=33)
    uri_fb2 = writer_fb2.publish(
        staging_exists=True, staging_size=1024,
        display_name="downloads_fallback.mp3", media_type=MediaType.AUDIO,
        container="mp3", subfolder="MediaDownloader"
    )
    assert_true("downloads" in res_fb2.inserts[0]["target"], "Fell back to MediaStore.Downloads collection")

    # =========================================================================
    # SUMMARY
    # =========================================================================
    print("\n" + "=" * 70)
    print(f"RESULTS: {passed_tests} / {total_tests} tests PASSED (0 failed).")
    print("ALL EMPIRICAL CHALLENGES SATISFIED.")
    print("=" * 70)

if __name__ == "__main__":
    run_all_tests()
