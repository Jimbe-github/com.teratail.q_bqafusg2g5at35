package com.teratail.q_bqafusg2g5at35;

import java.io.ByteArrayOutputStream;
import java.nio.*;
import java.util.StringJoiner;

public class Bytes {
  static byte[] of(int... values) {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    for(int b : values)
      baos.write(b);
    return baos.toByteArray();
  }

  static boolean allEquals(byte[] src, int... dst) {
    if(src.length != dst.length) return false;
    for(int i=0; i<dst.length; i++) if((src[i] & 0xff) != dst[i]) return false;
    return true;
  }
  static boolean equals(byte[] src, int offset, int... dst) {
    for(int i=0; i<dst.length; i++) if(offset+i >= src.length || (src[offset+i] & 0xff) != dst[i]) return false;
    return true;
  }

  static byte[] toLittleShort(int v) {
    return ByteBuffer.allocate(2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort((short) v)
            .array();
  }

  static short getShortInLittleEndianFrom(byte[] bytes, int index) {
    return ByteBuffer.wrap(bytes, index, 2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .getShort();
  }

  static byte[] join(byte[] a, byte[] b) {
    byte[] c = new byte[a.length + b.length];
    System.arraycopy(a, 0, c, 0, a.length);
    System.arraycopy(b, 0, c, a.length, b.length);
    return c;
  }

  static byte checkSum(byte[] bytes) {
    return checkSum(bytes, 0, bytes.length);
  }
  static byte checkSum(byte[] bytes, int start, int endExclude) {
    byte sum = 0;
    for(int i=start; i<endExclude; i++) sum += bytes[i];
    return (byte)(~sum + 1);
  }

  static String toString(byte[] bytes) {
    return toString(bytes, 0, bytes.length);
  }
  static String toString(byte[] bytes, int start, int endExclude) {
    StringJoiner sj = new StringJoiner(" ");
    for(int i = start; i < Math.min(bytes.length, endExclude); i++)
      sj.add(String.format("%02x", bytes[i] & 0xff));
    return sj.toString();
  }
}
