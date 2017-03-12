/* -*- mode: java; c-basic-offset: 2; indent-tabs-mode: nil -*- */

/*
  Copyright (c) Limor Fried/Ladyada for Adafruit Industries, with
  contributions from the open source community. Originally based on
  Thermal library from bildr.org. License: MIT.
  Ported to Processing by Gottfried Haider 2017.
*/

package gohai.simplereceiptprinter;

import processing.core.*;
import processing.serial.*;


/**
 *  Class for interfacing with UART-connected Thermal Receipt Printers
 *  Such as the Mini Thermal Receipt Printer (#597) from Adafruit
 */
public class SimpleReceiptPrinter {

  public static final int width = 384;            // 384 pixels max

  protected static final int ASCII_ESC = 27;      // Escape
  protected static final int ASCII_DC2 = 18;      // Device control 2

  protected static final int printDensity = 10;   // 100% (? can go higher, text is darker but fuzzy)
  protected static final int printBreakTime = 2;  // 500 uS

  protected PApplet parent;
  protected String port;
  protected float firmware;
  protected int baud;
  protected Serial serial;

  protected int byteTime;
  protected int column;
  protected int charHeight;
  protected int dotFeedTime;
  protected int dotPrintTime;
  protected int lineSpacing;
  protected int maxChunkHeight;
  protected int maxColumn;
  protected byte prevByte;
  protected long resumeTime;                      // in nanoseconds


  /**
   *  Create a new printer instance
   *  @param parent typically use "this"
   *  @param port Serial port to use
   *  @see list
   */
  public SimpleReceiptPrinter(PApplet parent, String port) {
    this(parent, port, 2.68f, 19200);
  }

  /**
   *  Create a new printer instance using custom settings
   *  Hold feed button on powerup to retrieve firmware version and baudrate.
   *  @param parent typically use "this"
   *  @param port Serial port to use
   *  @param firmware firmware version (default: 2.68)
   *  @param baud baudrate to use (default: 19200)
   *  @see list
   */
  public SimpleReceiptPrinter(PApplet parent, String port, float firmware, int baud) {
    this.parent = parent;
    this.port = port;
    this.firmware = firmware;
    this.baud = baud;

    serial = new Serial(parent, port, baud);

    // Number of microseconds to issue one byte to the printer. 11 bits
    // (not 8) to accommodate idle, start and stop bits. Idle time might
    // be unnecessary, but erring on side of caution here.
    byteTime = (((11 * 1000000) + (baud / 2)) / baud);

    begin();
  }

  /**
   *  Returns a list of all serial ports
   *  @return Array of Strings
   */
  public static String[] list() {
    return Serial.list();
  }

  /**
   *  Outputs text on the printer
   *  @param out String to print
   */
  public void print(String out) {
    for (int i=0; i < out.length(); i++) {
      write(out.charAt(i));
    }
  }

  /**
   *  Outputs text on the printer followed by a newline
   *  @param out String to print
   */
  public void println(String out) {
    print(out + "\n");
  }

  /**
   *  Outputs an image on the printer
   *  @param img PImage instance
   */
  public void printBitmap(PImage img) {
    int w = img.width;
    int h = img.height;
    if (width < w) {
      throw new RuntimeException("Printer can only handle images up to " + width + " pixels wide");
    }
    img.loadPixels();

    int rowBytes = (w + 7) / 8;                             // round up to next byte boundary
    int rowBytesClipped = (48 <= rowBytes) ? 48 : rowBytes; // 384 pixels max width
    int chunkHeightLimit = 256 / rowBytesClipped;

    // est. max rows to write at once, assuming 256 byte printer buffer
    if (maxChunkHeight < chunkHeightLimit) {
      chunkHeightLimit = maxChunkHeight;
    } else if (chunkHeightLimit < 1) {
      chunkHeightLimit = 1;
    }

    for (int rowStart=0; rowStart < h; rowStart += chunkHeightLimit) {
      // issue up to chunkHeightLimit rows at a time
      int chunkHeight = h - rowStart;
      if (chunkHeightLimit < chunkHeight) {
        chunkHeight = chunkHeightLimit;
      }
      writeBytes(ASCII_DC2, '*', chunkHeight, rowBytesClipped);

      for (int y=0; y < chunkHeight; y++) {
        for (int x=0; x < rowBytesClipped; x++) {
          byte c = (byte)0;
          for (int i=0; i < 8; i++) {
            if (x*8+i < w) {    // don't go out of bounds of our PImage
              // XXX: use pixels array
              if (parent.brightness(img.get(x*8+i, rowStart + y)) <= 127.0) {
                c |= 1 << (7-i);
             }
            }
          }
          timeoutWait();
          serial.write(c);
        }
      }
      timeoutSet(chunkHeight * dotPrintTime);
    }
    prevByte = '\n';
  }

  /**
   *  Feed paper
   *  @param lines number of lines to forward
   */
  public void feed(int lines) {
    if (2.64 <= firmware) {
      writeBytes(ASCII_ESC, 'd', lines);
      timeoutSet(dotFeedTime * charHeight);
      prevByte = '\n';
      column = 0;
    } else {
      // Feed manually; old firmware feeds excess lines
      while (0 < lines--) {
        write('\n');
      }
    }
  }

  /**
   *  Put the printer to sleep
   *  This reduces current consumption.
   *  @see wake
   */
  public void sleep() {
    // XXX: test
    // go to sleep after one second
    if (2.64 <= firmware) {
      writeBytes(ASCII_ESC, '8', 1, 0);
    } else {
      writeBytes(ASCII_ESC, '8', 1);
    }
  }

  /**
   *  Wake the printer up
   *  @see sleep
   */
  public void wake() {
    // XXX: test
    // reset the timeout counter
    timeoutSet(0);
    // wake
    writeBytes(255);
    if (2.64 <= firmware) {
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
      }
      writeBytes(ASCII_ESC, '8', 0, 0);
    } else {
      // Datasheet recommends a 50 mS delay before issuing further commands,
      // but in practice this alone isn't sufficient (e.g. text size/style
      // commands may still be misinterpreted on wake). A slightly longer
      // delay, interspersed with NUL chars (no-ops) seems to help.
      for (int i=0; i<10; i++) {
        writeBytes(0);
        timeoutSet(10000);
      }
    }
  }

  protected void begin() {
    begin(120);
  }

  protected void begin(int heatTime) {
    // The printer can't start receiving data immediately upon power up -
    // it needs a moment to cold boot and initialize. Allow at least 1/2
    // sec of uptime before printer can receive data.
    timeoutSet(500000);

    wake();
    reset();

    // ESC 7 n1 n2 n3 Setting Control Parameter Command
    // n1 = "max heating dots" 0-255 - max number of thermal print head
    //      elements that will fire simultaneously. Units = 8 dots (minus 1).
    //      Printer default is 7 (64 dots, or 1/6 of 384-dot width), this code
    //      sets it to 11 (96 dots, or 1/4 of width).
    // n2 = "heating time" 3-255 - duration that heating dots are fired.
    //      Units = 10 us. Printer default is 80 (800 us), this code sets it
    //      to value passed (default 120, or 1.2 ms - a little longer than
    //      the default because we've increased the max heating dots).
    // n3 = "heating interval" 0-255 - recovery time between groups of
    //      heating dots on line; possibly a function of power supply.
    //      Units = 10 us.  Printer default is 2 (20 us), this code sets it
    //      to 40 (throttled back due to 2A supply).
    // More heating dots = more peak current, but faster printing speed.
    // More heating time = darker print, but slower printing speed and
    // possibly paper 'stiction'. More heating interval = clearer print,
    // but slower printing speed.
    writeBytes(ASCII_ESC, '7');     // Esc 7 (print settings)
    writeBytes(11, heatTime, 40);   // Heating dots, heat time, heat interval

    // Print density description from manual:
    // DC2 # n Set printing density
    // D4..D0 of n is used to set the printing density. Density is
    // 50% + 5% * n(D4-D0) printing density.
    // D7..D5 of n is used to set the printing break time. Break time
    // is n(D7-D5)*250us.
    // (Unsure of the default value for either - not documented)
    writeBytes(ASCII_DC2, '#', (printBreakTime << 5) | printDensity);

    // Printer performance may vary based on the power supply voltage,
    // thickness of paper, phase of the moon and other seemingly random
    // variables. This method sets the times (in microseconds) for the
    // paper to advance one vertical 'dot' when printing and when feeding.
    // For example, in the default initialized state, normal-sized text is
    // 24 dots tall and the line spacing is 30 dots, so the time for one
    // line to be issued is approximately 24 * print time + 6 * feed time.
    // The default print and feed times are based on a random test unit,
    // but as stated above your reality may be influenced by many factors.
    // This lets you tweak the timing to avoid excessive delays and/or
    // overrunning the printer buffer.
    dotPrintTime = 30000;
    dotFeedTime = 2100;

    maxChunkHeight = 255;
  }

  protected void reset() {
    // init command
    writeBytes(ASCII_ESC, '@');

    prevByte = '\n';
    column = 0;
    maxColumn = 32;
    charHeight = 24;
    lineSpacing = 6;

    if (2.64 <= firmware) {
      // configure tab stops on recent printers
      writeBytes(ASCII_ESC, 'D');
      writeBytes(4, 8, 12, 16);
      writeBytes(20, 24, 28, 0);
    }
  }

  protected void timeoutSet(int microseconds) {
    resumeTime = System.nanoTime() + microseconds * 1000;
  }

  protected void timeoutWait() {
    while ((long)(System.nanoTime() - resumeTime) < 0L);
  }

  protected int write(char c) {
    return write((byte)c);
  }

  protected int write(byte c) {
    if (c != 0x13) {  // strip carriage returns
      timeoutWait();
      serial.write(c);

      int d = byteTime;
      if ((c == '\n') || (column == maxColumn)) {   // if newline or wrap
        d += (prevByte == '\n') ?
          ((charHeight+lineSpacing) * dotFeedTime) :             // feed line
          ((charHeight*dotPrintTime)+(lineSpacing*dotFeedTime)); // text line
        column = 0;
        c = '\n';     // treat wrap as newline on next pass
      } else {
        column++;
      }
      timeoutSet(d);
      prevByte = c;
    }
    return 1;
  }

  protected void writeBytes(int a) {
    timeoutWait();
    if (a < 0 || 255 < a) {
      throw new RuntimeException("Argument out of bounds (expected 0-255)");
    }
    serial.write(a);
    timeoutSet(byteTime);
  }

  protected void writeBytes(int a, int b) {
    timeoutWait();
    if (a < 0 || 255 < a | b < 0 | 255 < b) {
      throw new RuntimeException("Argument out of bounds (expected 0-255)");
    }
    serial.write(a);
    serial.write(b);
    timeoutSet(2 * byteTime);
  }

  protected void writeBytes(int a, int b, int c) {
    timeoutWait();
    if (a < 0 || 255 < a | b < 0 | 255 < b | c < 0 | 255 < c) {
      throw new RuntimeException("Argument out of bounds (expected 0-255)");
    }
    serial.write(a);
    serial.write(b);
    serial.write(c);
    timeoutSet(3 * byteTime);
  }

  protected void writeBytes(int a, int b, int c, int d) {
    timeoutWait();
    if (a < 0 || 255 < a | b < 0 | 255 < b | c < 0 | 255 < c | d < 0 | 255 < d) {
      throw new RuntimeException("Argument out of bounds (expected 0-255)");
    }
    serial.write(a);
    serial.write(b);
    serial.write(c);
    serial.write(d);
    timeoutSet(4 * byteTime);
  }
}
