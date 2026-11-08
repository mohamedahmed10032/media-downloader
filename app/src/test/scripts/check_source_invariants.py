#!/usr/bin/env python3
"""
Direct Static & Invariant Checker on MimeTypes.kt and MediaStoreWriter.kt
"""

import sys
import os
import re

MIMETYPES_PATH = "/home/antigravity/mohamedahmed10032/media-downloader/app/src/main/java/com/melmeligy/mediadownloader/core/util/MimeTypes.kt"
MEDIASTOREWRITER_PATH = "/home/antigravity/mohamedahmed10032/media-downloader/app/src/main/java/com/melmeligy/mediadownloader/download/MediaStoreWriter.kt"

def check_invariants():
    print("Checking static invariants in Kotlin source files...")

    # --- 1. MimeTypes.kt ---
    with open(MIMETYPES_PATH, "r", encoding="utf-8") as f:
        mimes_code = f.read()

    assert "val ext = container.lowercase().trimStart('.')" in mimes_code, "MimeTypes.kt must sanitize ext with lowercase().trimStart('.')"
    assert '"webm" -> "audio/webm"' in mimes_code, "MimeTypes.kt must map webm to audio/webm under AUDIO"
    assert '"mp4" -> "audio/mp4"' in mimes_code, "MimeTypes.kt must map mp4 to audio/mp4 under AUDIO"
    assert '"3gp", "3gpp" -> "audio/3gpp"' in mimes_code, "MimeTypes.kt must map 3gp/3gpp to audio/3gpp under AUDIO"
    assert '"mka" -> "audio/x-matroska"' in mimes_code, "MimeTypes.kt must map mka to audio/x-matroska under AUDIO"
    assert 'else -> runCatching { MimeTypeMap.getSingleton()?.getMimeTypeFromExtension(ext) }' in mimes_code, "MimeTypes.kt must use runCatching for MimeTypeMap fallback"
    assert '?.takeIf { it.startsWith("audio/") } ?: "audio/mpeg"' in mimes_code, "MimeTypes.kt must guard audio fallback"
    print("  [OK] MimeTypes.kt static invariants verified.")

    # --- 2. MediaStoreWriter.kt ---
    with open(MEDIASTOREWRITER_PATH, "r", encoding="utf-8") as f:
        writer_code = f.read()

    assert "val cleanSub = subfolder.trim().trim('/')" in writer_code, "cleanSub must trim whitespace and slashes"
    assert 'val relativePath = if (cleanSub.isEmpty()) "$directory/" else "$directory/$cleanSub/"' in writer_code, "relativePath must ensure trailing slash"
    assert "val title = displayName.substringBeforeLast('.', displayName)" in writer_code, "title must strip extension"
    assert "System.currentTimeMillis() / 1000" in writer_code, "DATE_ADDED must use epoch seconds (/ 1000)"
    assert "if (type == MediaType.AUDIO) {\n                    put(MediaStore.Audio.Media.IS_MUSIC, 1)\n                }" in writer_code or "if (type == MediaType.AUDIO) {\n                    put(MediaStore.Audio.Media.IS_MUSIC, 1)" in writer_code, "IS_MUSIC must only be set for AUDIO"

    # Verify IS_PENDING is wrapped in runCatching
    is_pending_match = re.search(r'runCatching\s*\{\s*val done = ContentValues\(\)\.apply\s*\{\s*put\(MediaStore\.MediaColumns\.IS_PENDING,\s*0\)\s*\}\s*resolver\.update\(itemUri,\s*done,\s*null,\s*null\)\s*\}', writer_code)
    assert is_pending_match is not None, "IS_PENDING update MUST be wrapped in runCatching"

    # Verify catch block deletes itemUri
    catch_delete_match = re.search(r'catch\s*\(\w+:\s*Exception\)\s*\{\s*runCatching\s*\{\s*resolver\.delete\(itemUri,\s*null,\s*null\)\s*\}', writer_code)
    assert catch_delete_match is not None, "catch block must clean up itemUri"

    # Verify stagingFile.delete() is called after try-catch
    assert "runCatching { stagingFile.delete() }" in writer_code, "stagingFile.delete() must be executed"

    print("  [OK] MediaStoreWriter.kt static invariants verified.")
    print("ALL INVARIANTS PASS.")

if __name__ == "__main__":
    check_invariants()
