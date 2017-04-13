/*
 * This example shows how to use a Thermal Receipt Printer with Processing
 * The part used is #597 from Adafruit (labeled "Model BT-2")
 *
 * Only attach the yellow wire to the TX pin of your Single Board Computer
 * and the black wire to a GND pin. Don't connect the green wire to the RX
 * pin, since this is not used any might damage your SBC due to the higher
 * voltage used by the printer.
 *
 * The printer draws a considerable amount of power, so make sure to power
 * it from an external 5V ~ 2A power supply.
 */

import gohai.simplereceiptprinter.*;
import processing.serial.*;
SimpleReceiptPrinter printer;

void setup() {
  String[] ports = SimpleReceiptPrinter.list();
  println("Available serial ports:");
  printArray(ports);
  String port = ports[0];
  println("Attempting to use " + port);
  printer = new SimpleReceiptPrinter(this, port);

  /*
   * If you're having problems, try holding the feed button while
   * connecting the printer to power. This will print a test page
   * that includes the expected baud rate as well as the firmware
   * number. If the values differ from the default 19200 baud and
   * version 2.68 you can pass them to the constructor like this:
   * printer = new SimpleReceiptPrinter(this, ports[0], 2.68, 19200);
   */

  printer.println("Hello");
  printer.feed(3);

  printer.println("This is a long text and will wrap around after 32 characters");
  printer.feed(3);

  // get printable width
  println("Maximum width: " + printer.width);

  // draw to the screen and print the graphic
  line(0, 0, 100, 100);
  line(0, 100, 100, 0);
  printer.printBitmap(get());
  printer.feed(3);
}

void draw() {
}
