//
// ImarisReader.java
//

/*
LOCI Bio-Formats package for reading and converting biological file formats.
Copyright (C) 2005-@year@ Melissa Linkert, Curtis Rueden, Chris Allan,
Eric Kjellman and Brian Loranger.

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU Library General Public License as published by
the Free Software Foundation; either version 2 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU Library General Public License for more details.

You should have received a copy of the GNU Library General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/

package loci.formats.in;

import java.awt.image.BufferedImage;
import java.io.*;
import loci.formats.*;

/**
 * ImarisReader is the file format reader for Bitplane Imaris files.
 * Specifications available at
 * http://flash.bitplane.com/support/faqs/faqsview.cfm?inCat=6&inQuestionID=104
 *
 * @author Melissa Linkert linkert at wisc.edu
 */
public class ImarisReader extends FormatReader {

  // -- Constants --

  /** Magic number; present in all files. */
  private static final int IMARIS_MAGIC_NUMBER = 5021964;

  /** Specifies endianness. */
  private static final boolean IS_LITTLE = false;

  // -- Fields --

  /** Current file. */
  protected RandomAccessStream in;

  /** Number of image planes in the file. */
  protected int numImages = 0;

  /** Image dimensions: width, height, z, channels. */
  private int[] dims;

  /** Offsets to each image. */
  private int[] offsets;

  // -- Constructor --

  /** Constructs a new Imaris reader. */
  public ImarisReader() { super("Bitplane Imaris", "ims"); }

  // -- FormatReader API methods --

  /** Initializes the given Imaris file. */
  protected void initFile(String id) throws FormatException, IOException {
    if (debug) debug("ImarisReader.initFile(" + id + ")");
    super.initFile(id);
    in = new RandomAccessStream(id);
    in.order(IS_LITTLE);

    dims = new int[4];

    long magic = in.readInt();
    if (magic != IMARIS_MAGIC_NUMBER) {
      throw new FormatException("Imaris magic number not found.");
    }

    int version = in.readInt();
    addMeta("Version", new Integer(version));
    in.readInt();

    byte[] name = new byte[128];
    in.read(name);
    String iName = new String(name);
    addMeta("Image name", iName);

    dims[0] = in.readShort();
    dims[1] = in.readShort();
    dims[2] = in.readShort();

    in.skipBytes(2);

    dims[3] = in.readInt();
    in.skipBytes(2);

    byte[] date = new byte[32];
    in.read(date);
    String origDate = new String(date);
    addMeta("Original date", origDate);

    float dx = in.readFloat();
    float dy = in.readFloat();
    float dz = in.readFloat();
    in.readShort();

    byte[] com = new byte[128];
    in.read(com);
    String comment = new String(com);
    addMeta("Image comment", comment);
    int isSurvey = in.readInt();
    addMeta("Survey performed", isSurvey == 0 ? "true" : "false");

    numImages = dims[2] * dims[3];
    offsets = new int[numImages];

    for (int i=0; i<dims[3]; i++) {
      int offset = 332 + ((i + 1) * 168) + (i * dims[0] * dims[1] * dims[2]);
      for (int j=0; j<dims[2]; j++) {
        offsets[i*dims[2] + j] = offset + (j * dims[0] * dims[1]);
      }
    }

    sizeX[0] = dims[0];
    sizeY[0] = dims[1];
    sizeZ[0] = dims[2];
    sizeC[0] = dims[3];
    sizeT[0] = numImages / (dims[2] * dims[3]);
    currentOrder[0] = "XYZCT";

    // The metadata store we're working with.
    MetadataStore store = getMetadataStore(id);

    pixelType[0] = FormatReader.UINT8;
    store.setPixels(
      new Integer(dims[0]),
      new Integer(dims[1]),
      new Integer(dims[2]),
      new Integer(dims[3]),
      new Integer(1),
      new Integer(pixelType[0]),
      new Boolean(!IS_LITTLE),
      "XYZCT",
      null);

    store.setImage(
      (String) getMeta("Image name"),
      (String) getMeta("Original date"),
      (String) getMeta("Image comment"),
      null);

    store.setDimensions(
      new Float(dx),
      new Float(dy),
      new Float(dz),
      new Float(1),
      new Float(1),
      null);
  }

  // -- IFormatReader API methods --

  /** Checks if the given block is a valid header for an Imaris file. */
  public boolean isThisType(byte[] block) {
    return DataTools.bytesToInt(block, 0, 4, IS_LITTLE) == IMARIS_MAGIC_NUMBER;
  }

  /** Determines the number of images in the given Imaris file. */
  public int getImageCount(String id) throws FormatException, IOException {
    if (!id.equals(currentId)) initFile(id);
    return numImages;
  }

  /** Checks if the images in the file are RGB. */
  public boolean isRGB(String id) throws FormatException, IOException {
    return false;
  }

  /** Return true if the data is in little-endian format. */
  public boolean isLittleEndian(String id) throws FormatException, IOException {
    return IS_LITTLE;
  }

  /** Returns whether or not the channels are interleaved. */
  public boolean isInterleaved(String id) throws FormatException, IOException {
    return false;
  }

  /**
   * Obtains the specified image from the
   * given Imaris file as a byte array.
   */
  public byte[] openBytes(String id, int no)
    throws FormatException, IOException
  {
    if (!id.equals(currentId)) initFile(id);

    if (no < 0 || no >= getImageCount(id)) {
      throw new FormatException("Invalid image number: " + no);
    }

    in.seek(offsets[no]);
    byte[] data = new byte[dims[0] * dims[1]];

    int row = dims[1] - 1;
    for (int i=0; i<dims[1]; i++) {
      in.read(data, row*dims[0], dims[0]);
      row--;
    }

    return data;
  }

  /** Obtains the specified image from the given Imaris file. */
  public BufferedImage openImage(String id, int no)
    throws FormatException, IOException
  {
    return ImageTools.makeImage(openBytes(id, no), dims[0], dims[1], 1, false);
  }
 
  /* @see IFormatReader#close(boolean) */
  public void close(boolean fileOnly) throws FormatException, IOException {
    if (fileOnly && in != null) in.close();
    else if (!fileOnly) close();
  }

  /** Closes any open files. */
  public void close() throws FormatException, IOException {
    if (in != null) in.close();
    in = null;
    currentId = null;
  }

  // -- IFormatHandler API methods --

  /* @see loci.formats.IFormatHandler#isThisType(String, boolean) */
  public boolean isThisType(String name, boolean open) {
    if (!super.isThisType(name, open)) return false; // check extension
    if (!open) return true; // not allowed to check the file contents
    try {
      DataInputStream dis = new DataInputStream(new FileInputStream(name));
      byte[] b = new byte[4];
      dis.readFully(b);
      dis.close();
      return isThisType(b);
    }
    catch (IOException e) { return false; }
  }

  // -- Main method --

  public static void main(String[] args) throws FormatException, IOException {
    new ImarisReader().testRead(args);
  }

}
