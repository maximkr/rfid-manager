This Andoroid application for Chainway C5 allows you to quickly write data to UHF RFID tags without a specialized RFID printer.

Problem: To use an RFID tag, you need to write the RFID code to the tag and (preferably) print this code on the tag itself. Then the tag can be read by a regular barcode scanner, and it can be found in the warehouse as an RFID tag using an RFID scanner such as the Chainway C5. An RFID printer can print text on the tag and write data to the tag's memory, but RFID printers are quite expensive and bulky, so it does not make sense to buy them for RFID implementation in a small warehouse. With the Chainway C5 and this Android application, you can quickly write data to an RFID tag without using a printer.

1) Print a QR/Datamatrix code on the RFID tag using a printer or marker, or simply stick a sticker with a QR/Datamatrix code on top of it.
2) Bring the Chainway C5 close to the tag and press the trigger. The application will read the RFID tag and immediately record it as RFID in one step.

Since the software for working with UHF works with data in hex format, use codes in hex format for compatibility. For example, use tags
E001, E002, E003...., E009, E010, E011. Names such as W001 will not work because W0 is not a hex digit.
