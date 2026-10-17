# Synthetic fixtures (no third-party media)

clip.mp4: 0.4 seconds of 64x64 blue frames (H.264) plus a 440Hz sine (AAC).
picture.png: one 64x64 red frame. Generated with FFmpeg lavfi; no platform content.
Tests transfer the real files over loopback HTTP and compare bytes. Android tests
inspect tracks; CI also decodes the fixtures with FFmpeg. Neither is a live-site test.
video-only.mp4 and audio-only.m4a are stream-copy splits of clip.mp4 for native
FFmpeg merge and audio-only regression tests.
