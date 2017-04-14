/*
 * This example shows how to use a Thermal Receipt Printer with Processing
 * The part used is #597 from Adafruit (labeled "Model BT-2")
 *
 * Only attach the yellow wire to the TXD pin of your Single Board Computer
 * and the black wire to a GND pin. Don't connect the green wire to the RXD
 * pin, since this is not used and might damage your SBC due to the higher
 * voltage used by the printer.
 *
 * The printer draws a considerable amount of power, so make sure to power
 * it from an external 5-9V ~ 1.5A power supply.
 */

import gohai.simplereceiptprinter.*;
import processing.serial.*;
SimpleReceiptPrinter printer;
PImage img;

void setup() {
  size(384, 216);
  String[] ports = SimpleReceiptPrinter.list();
  println("Available serial ports:");
  printArray(ports);
  // you might need to use a different port
  String port = ports[0];
  println("Attempting to use " + port);
  printer = new SimpleReceiptPrinter(this, port);

  /*
   * If you're having problems, try holding the feed button while
   * connecting the printer to power. This will print a test page
   * that includes the expected baud rate as well as the firmware
   * number. If the values differ from the default 19200 baud and
   * version 2.68 you can pass them to the constructor like this:
   * printer = new SimpleReceiptPrinter(this, port, 2.68, 19200);
   */

  img = loadImage("moonwalk.jpg");
  img.resize(printer.width, 0);
  dither(img);
  image(img, 0, 0);
  printer.printBitmap(get());
  printer.feed(3);
}

/*
 * This implements Bill Atkinson's dithering algorithm
 */
void dither(PImage img) {
  img.loadPixels();

  for (int y=0; y < img.height; y++) {
    for (int x=0; x < img.width; x++) {
      // set current pixel and error
      float bright = brightness(img.pixels[y*img.width+x]);
      float err;
      if (bright <= 127) {
        img.pixels[y*img.width+x] = 0x000000;
        err = bright;
      } else {
        img.pixels[y*img.width+x] = 0xffffff;
        err = bright-255;
      }
      // distribute error
      int offsets[][] = new int[][]{{1, 0}, {2, 0}, {-1, 1}, {0, 1}, {1, 1}, {0, 2}};
      for (int i=0; i < offsets.length; i++) {
        int x2 = x + offsets[i][0];
        int y2 = y + offsets[i][1];
        if (x2 < img.width && y2 < img.height) {
          float bright2 = brightness(img.pixels[y2*img.width+x2]);
          bright2 += err * 0.125;
          bright2 = constrain(bright2, 0.0, 255.0);
          img.pixels[y2*img.width+x2] = color(bright2);
        }
      }
    }
  }

  img.updatePixels();
}
