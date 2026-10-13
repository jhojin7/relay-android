package com.hush.voice;

import java.io.*;

/** 16 kHz, mono, signed PCM16 WAV; header is repairable after process loss. */
final class WavFile implements AutoCloseable {
  private final RandomAccessFile out;
  private long samples;

  WavFile(File file) throws IOException {
    out = new RandomAccessFile(file, "rw");
    out.setLength(0);
    out.write(new byte[44]);
    header(out, 0);
  }

  void write(short[] data, int count) throws IOException {
    out.seek(44 + samples * 2);
    byte[] pcm = new byte[count * 2];
    for (int i = 0; i < count; i++) {
      pcm[i * 2] = (byte) data[i];
      pcm[i * 2 + 1] = (byte) (data[i] >>> 8);
    }
    out.write(pcm);
    samples += count;
  }

  void checkpoint() throws IOException {
    header(out, samples * 2);
    out.getFD().sync();
  }

  int seconds() {
    return (int) (samples / 16000);
  }

  long samples() {
    return samples;
  }

  @Override
  public void close() throws IOException {
    try {
      checkpoint();
    } finally {
      out.close();
    }
  }

  static int repair(File file) throws IOException {
    try (RandomAccessFile f = new RandomAccessFile(file, "rw")) {
      long bytes = Math.max(0, f.length() - 44);
      bytes -= bytes % 2;
      if (bytes == 0) throw new IOException("No recorded audio");
      header(f, bytes);
      return (int) (bytes / 32000);
    }
  }

  private static void header(RandomAccessFile f, long bytes) throws IOException {
    f.seek(0);
    f.writeBytes("RIFF");
    integer(f, bytes + 36);
    f.writeBytes("WAVEfmt ");
    integer(f, 16);
    small(f, 1);
    small(f, 1);
    integer(f, 16000);
    integer(f, 32000);
    small(f, 2);
    small(f, 16);
    f.writeBytes("data");
    integer(f, bytes);
  }

  private static void integer(RandomAccessFile f, long value) throws IOException {
    for (int i = 0; i < 4; i++) f.write((int) (value >>> (i * 8)) & 255);
  }

  private static void small(RandomAccessFile f, int value) throws IOException {
    f.write(value & 255);
    f.write((value >>> 8) & 255);
  }
}
