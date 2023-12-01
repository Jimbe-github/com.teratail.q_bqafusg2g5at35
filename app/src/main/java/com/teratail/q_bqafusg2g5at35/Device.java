package com.teratail.q_bqafusg2g5at35;

import android.hardware.usb.*;
import android.util.Log;

import java.io.*;
import java.nio.*;
import java.util.*;

class Frame {
  enum Type {
    UNKNOWN, ACK, ERR, DATA;
  }
  private static final Frame ACK = new Frame(Bytes.of(0, 0, 0xff, 0, 0xff, 0), Type.ACK);
  private static final Frame ERR = new Frame(Bytes.of(0, 0, 0xff, 0xff, 0xff), Type.ERR);

  final byte[] frame;
  final Type type;

  static Frame createRequest(byte[] cmd) {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try {
      byte[] lens = Bytes.toLittleShort(cmd.length);
      baos.write(Bytes.of(0, 0, 0xff, 0xff, 0xff));
      baos.write(lens);
      baos.write(Bytes.checkSum(lens));
      baos.write(cmd);
      baos.write(Bytes.checkSum(cmd));
      baos.write(0);
    } catch(IOException ignore) {
      //no process
    }
    return new Frame(baos.toByteArray(), Type.DATA);
  }
  static Frame createResponce(byte[] frame) {
    if(Arrays.equals(frame, ACK.frame)) return ACK;
    if(Arrays.equals(frame, ERR.frame)) return ERR;
    Type type = Bytes.equals(frame, 3, 0xff, 0xff) ? Type.DATA : Type.UNKNOWN;
    if(type == Type.DATA) {
      if(Bytes.checkSum(frame, 5, 7+1) != 0) {
        throw new IllegalStateException("length checksum error");
      }
      int len = Bytes.getShortInLittleEndianFrom(frame, 5);
      if(Bytes.checkSum(frame, 8, 8+len+1) != 0) {
        throw new IllegalStateException("data checksum error");
      }
    }
    return new Frame(frame, type);
  }
  private Frame(byte[] frame, Type type) {
    this.frame = frame;
    this.type = type;
  }

  boolean isAck() {
    return type == Type.ACK;
  }
  boolean isData() {
    return type == Type.DATA;
  }
  byte[] getData() {
    int len = Bytes.getShortInLittleEndianFrom(frame, 5);
    return Arrays.copyOfRange(frame, 8, 8+len);
  }
}

class Transport implements AutoCloseable {
  private final UsbDeviceConnection con;
  private final UsbEndpoint in, out;
  private Logger log;

  Transport(UsbManager manager, UsbDevice device) throws IOException {
    con = manager.openDevice(device);
    if(con == null) throw new IOException("Connection is null");

    UsbInterface usbInterface = device.getInterface(0);
    if(!con.claimInterface(usbInterface, true)) {
      con.close();
      throw new IOException("claimInterface is false");
    }

    UsbEndpoint in = null, out = null;
    for(int i=0; i<usbInterface.getEndpointCount(); i++) {
      UsbEndpoint ep = usbInterface.getEndpoint(i);
      if(ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
      if(ep.getDirection() == UsbConstants.USB_DIR_IN) {
        in = ep;
      } else if (ep.getDirection() == UsbConstants.USB_DIR_OUT) {
        out = ep;
      }
    }
    this.in = in;
    this.out = out;
  }

  void setLogger(Logger log) {
    this.log = log;
  }

  void write(byte[] bytes) throws IOException {
    if(log != null) log.debug(">>>> " + Bytes.toString(bytes));
    if(con.bulkTransfer(out, bytes, bytes.length, 100) != bytes.length) throw new IOException("send error");
  }

  byte[] read() throws IOException {
    return read(100);
  }
  byte[] read(int timeout) throws IOException {
    byte[] buffer = new byte[256+11];
    int len = con.bulkTransfer(in, buffer, buffer.length, timeout); //timeout=[ms]
    if(len < 0) throw new IOException("receive error: len=" + len);
    if(log != null) log.debug("<<<< " + Bytes.toString(buffer, 0, len));
    return Arrays.copyOfRange(buffer, 0, len);
  }

  @Override
  public void close() {
    if(con != null) con.close();
  }
}

class CommunicationException extends IOException {
  private static final Map<Integer,String> map = new HashMap<>();
  static {
    map.put(0x00000000, "NO_ERROR");
    map.put(0x00000001, "PROTOCOL_ERROR");
    map.put(0x00000002, "PARITY_ERROR");
    map.put(0x00000004, "CRC_ERROR");
    map.put(0x00000008, "COLLISION_ERROR");
    map.put(0x00000010, "OVERFLOW_ERROR");
    map.put(0x00000040, "TEMPERATURE_ERROR");
    map.put(0x00000080, "RECEIVE_TIMEOUT_ERROR");
    map.put(0x00000100, "CRYPTO1_ERROR");
    map.put(0x00000200, "RFCA_ERROR");
    map.put(0x00000400, "RF_OFF_ERROR");
    map.put(0x00000800, "TRANSMIT_TIMEOUT_ERROR");
    map.put(0x80000000, "RECEIVE_LENGTH_ERROR");
  }
  final int status;
  CommunicationException(int status) {
    super(map.getOrDefault(status, String.format("0x%08x", status)));
    this.status = status;
  }
  boolean isReceiveTimeout() { return status == 0x00000080; }
}

class StatusException extends IOException {
  private static final String[] messages = {
          "SUCCESS", "PARAMETER_ERROR", "PB_ERROR", "RFCA_ERROR",
          "TEMPERATURE_ERROR", "PWD_ERROR", "RECEIVE_ERROR",
          "COMMANDTYPE_ERROR"
  };
  private static String getMessage(int errno) {
    if(0 <= errno && errno < messages.length) return messages[errno];
    return String.format("UNKNOWN STATUS ERROR 0x%02x", errno&0xff);
  }
  StatusException(int errno) {
    super(getMessage(errno));
  }
}

interface Logger {
  void log(String s);
  void debug(String s);
  void error(String s);
}

class Chipset implements AutoCloseable {
  static final byte[] ACK = Bytes.of(0, 0, 0xff, 0, 0xff, 0);

  enum CMD {
    InSetRF(0x00), InSetProtocol(0x02), InCommRF(0x04), SwitchRF(0x06),
    MaintainFlash(0x10), ResetDevice(0x12), GetFirmwareVersion(0x20),
    GetPDDataVersion(0x22), GetProperty(0x24), InGetProtocol(0x26),
    GetCommandType(0x28), SetCommandType(0x2a), InSetRCT(0x30), InGetRCT(0x32),
    GetPDData(0x34), ReadRegister(0x36), TgSetRF(0x40), TgSetProtocol(0x42),
    TgSetAuto(0x44), TgSetRFOff(0x46), TgCommRF(0x48), TgGetProtocol(0x50),
    TgSetRCT(0x60), TgGetRCT(0x62), Diagnose(0xf0);

    final byte code;

    CMD(int code) {
      this.code = (byte)code;
    }
  }

  private final Transport transport;
  private final Logger log;

  Chipset(Transport transport, Logger log) throws IOException {
    this.transport = transport;
    this.log = log;

    transport.setLogger(log);

    transport.write(Chipset.ACK); //通信の途中だったかも知れないので送ってみる
    try {
      //タイムアウトが発生するまで
      while(true) {
        byte[] data = transport.read(10); //何か残っているなら捨てる
        log.debug(String.format("cleared garbage %s", Bytes.toString(data)));
      }
    } catch(IOException ignore) {
      //no process
    }

    set_command_type(1);
    //get_firmware_version(null);
    //get_pd_data_version();
    switch_rf(false);
  }

  @Override
  public void close() throws IOException {
    switch_rf(false);
    transport.write(Chipset.ACK);
    transport.close();
  }

  byte[] send_command(CMD cmd, byte[] cmd_data) throws IOException {
    byte[] req = Bytes.join(Bytes.of(0xd6, cmd.code), cmd_data);
    transport.write(Frame.createRequest(req).frame);

    Frame ack = Frame.createResponce(transport.read());
    if(!ack.isAck()) {
      log.error("expected ACK but got " + ack.type);
      return null;
    }
    Frame rsp = Frame.createResponce(transport.read());
    if(!rsp.isData()) {
      log.error("expected DATA but got " + rsp.type);
      return null;
    }
    byte[] data = rsp.getData();
    if(!Bytes.equals(data, 0, 0xd7, cmd.code+1)) {
      log.error(String.format("expected rsp code D7%02X but %02X%02X",
              cmd.code+1, data[0]&0xff, data[1]&0xff));
      return null;
    }
    return Arrays.copyOfRange(data, 2, data.length);
  }

  void set_command_type(int command_type) throws IOException {
    byte[] res = send_command(CMD.SetCommandType, Bytes.of(command_type));
    if(res == null) return;
    if(res[0] != 0) throw new StatusException(res[0]);
  }

  int get_firmware_version(Integer option) throws IOException {
    if(option != null && option != 60 && option != 61 && option != 0x80) throw new IllegalArgumentException("option=" + option);
    byte[] res = send_command(CMD.GetFirmwareVersion, option == null ? new byte[0] : Bytes.of(option));
    if(res == null) return 0;
    Log.d("", String.format("firmware version %x.%02x", res[1], res[0]));
    return Bytes.getShortInLittleEndianFrom(res, 0);
  }

  int get_pd_data_version() throws IOException {
    byte[] res = send_command(CMD.GetPDDataVersion, new byte[0]);
    if(res == null) return 0;
    Log.d("", String.format("package data format %x.%02x", res[1], res[0]));
    return Bytes.getShortInLittleEndianFrom(res, 0);
  }

  void switch_rf(boolean on) throws IOException {
    byte[] res = send_command(CMD.SwitchRF, Bytes.of(on?1:0));
    if(res == null) return;
    if(res[0] != 0) throw new StatusException(res[0]);
  }

  private static final Map<String,byte[]> settings = new HashMap<>();
  static {
    settings.put("212F", new byte[]{1, 1, 15, 1});
    settings.put("424F", new byte[]{1, 2, 15, 2});
    settings.put("106A", new byte[]{2, 3, 15, 3});
    settings.put("212A", new byte[]{4, 4, 15, 4});
    settings.put("424A", new byte[]{5, 5, 15, 5});
    settings.put("106B", new byte[]{3, 7, 15, 7});
    settings.put("212B", new byte[]{3, 8, 15, 8});
    settings.put("424B", new byte[]{3, 9, 15, 9});
  }

  void in_set_rf(String brty) throws IOException{
    in_set_rf(brty, brty);
  }
  void in_set_rf(String brty_send, String brty_recv) throws IOException {
    byte[] send = settings.get(brty_send);
    if(send == null) throw new IllegalArgumentException("brty_send=" + brty_send);
    byte[] recv = settings.get(brty_recv == null ? brty_send : brty_recv);
    if(recv == null) throw new IllegalArgumentException("brty_recv=" + brty_recv);
    byte[] cmd = new byte[4];
    System.arraycopy(send, 0, cmd, 0, 2);
    System.arraycopy(recv, 2, cmd, 2, 2);
    byte[] res = send_command(CMD.InSetRF, cmd);
    if(res == null) return;
    if(res[0] != 0) throw new StatusException(res[0]);
  }

  static class InsetProtocolParams {
    private final Map<Byte,Byte> map = new TreeMap<>(); //キー昇順

    InsetProtocolParams initial_guard_time(int v) { return put(0x00, v); }
    InsetProtocolParams add_crc(int v) { return put(0x01, v); }
    InsetProtocolParams check_crc(int v) { return put(0x02, v); }
    InsetProtocolParams multi_card(int v) { return put(0x03, v); }
    InsetProtocolParams add_parity(int v) { return put(0x04, v); }
    InsetProtocolParams check_parity(int v) { return put(0x05, v); }
    InsetProtocolParams bitwise_anticoll(int v) { return put(0x06, v); }
    InsetProtocolParams last_byte_bit_count(int v) { return put(0x07, v); }
    InsetProtocolParams mifare_crypto(int v) { return put(0x08, v); }
    InsetProtocolParams add_sof(int v) { return put(0x09, v); }
    InsetProtocolParams check_sof(int v) { return put(0x0a, v); }
    InsetProtocolParams add_eof(int v) { return put(0x0b, v); }
    InsetProtocolParams check_eof(int v) { return put(0x0c, v); }
    //InsetProtocolParams rfu(int v) { return put(0x0d, v); }
    InsetProtocolParams deaf_time(int v) { return put(0x0e, v); }
    InsetProtocolParams continuous_receive_mode(int v) { return put(0xf, v); }
    InsetProtocolParams min_len_for_crm(int v) { return put(0x10, v); }
    InsetProtocolParams type_1_tag_rrdd(int v) { return put(0x11, v); }
    InsetProtocolParams rfca(int v) { return put(0x12, v); }
    InsetProtocolParams guard_time(int v) { return put(0x13, v); }

    private InsetProtocolParams put(int key, int value) {
      map.put((byte)key, (byte)value);
      return this;
    }

    byte[] toByteArray() {
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      for(Map.Entry<Byte,Byte> entry : map.entrySet()) { //キー昇順
        baos.write(entry.getKey());
        baos.write(entry.getValue());
      }
      return baos.toByteArray();
    }

    static InsetProtocolParams getDefault() {
      return new InsetProtocolParams()
              .initial_guard_time(24).add_crc(1).check_crc(1).multi_card(0)
              .add_parity(0).check_parity(0).bitwise_anticoll(0)
              .last_byte_bit_count(8).mifare_crypto(0).add_sof(0).check_sof(0)
              .add_eof(0).check_eof(0).deaf_time(4).continuous_receive_mode(0)
              .min_len_for_crm(0).type_1_tag_rrdd(0).rfca(0).guard_time(6);
    }
  }

  void in_set_protocol(InsetProtocolParams params) throws IOException {
    byte[] res = send_command(CMD.InSetProtocol, params.toByteArray());
    if(res == null) return;
    if(res[0] != 0) throw new StatusException(res[0]);
  }

  byte[] in_comm_rf(byte[] data, int timeout) throws IOException {
    timeout = Math.min((timeout+(timeout>0?1:0)) * 10, 0xffff);
    byte[] res = send_command(CMD.InCommRF, Bytes.join(Bytes.toLittleShort(timeout), data));
    if(res == null) return null;
    int status = ByteBuffer.wrap(res).order(ByteOrder.LITTLE_ENDIAN).getInt();
    if(status != 0) throw new CommunicationException(status);
    return Arrays.copyOfRange(res, 5, res.length);
  }
}

public class Device implements AutoCloseable {
  private final Chipset chipset;
  private final Logger log;
  final String chipsetName;

  Device(Transport transport, Logger logger) throws IOException {
    this.chipset = new Chipset(transport, logger);
    this.log = logger;

    int firmver = chipset.get_firmware_version(null);
    chipsetName = String.format("NFC Port-100 v%x.%02x", firmver>>8, firmver&0xff);
    log.log(chipsetName);
  }

  @Override
  public void close() throws Exception {
    chipset.close();
  }

  //Sense for a Type F Target is supported for 212 and 424 kbps.
  byte[] sense_ttf(String target) throws IOException {
    log.debug("polling for NFC-F technology");

    chipset.in_set_rf(target);
    chipset.in_set_protocol(Chipset.InsetProtocolParams.getDefault().initial_guard_time(28));

    byte[] sensf_req = Bytes.of(0, 0xff, 0xff, 1, 0);
    log.debug(String.format("send SENSF_REQ %s", Bytes.toString(sensf_req)));

    try {
      byte[] frame = chipset.in_comm_rf(Bytes.join(Bytes.of(sensf_req.length+1), sensf_req), 10);

      if(18 <= frame.length && frame[0] == frame.length && frame[1] == 1) {
        log.debug(String.format("rcvd SENSF_RES %s", Bytes.toString(frame, 1, frame.length)));
        return Arrays.copyOfRange(frame, 1, frame.length);
      }
    } catch(CommunicationException e) {
      if(!e.isReceiveTimeout()) log.debug(e.getMessage());
    }
    return null;
  }
}
