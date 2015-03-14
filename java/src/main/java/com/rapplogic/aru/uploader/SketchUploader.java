/**
 * Copyright (c) 2015 Andrew Rapp. All rights reserved.
 *
 * This file is part of arduino-remote-uploader
 *
 * arduino-remote-uploader is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * arduino-remote-uploader is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with arduino-remote-uploader.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.rapplogic.aru.uploader;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;

import org.apache.log4j.Logger;

import com.rapplogic.aru.core.Page;
import com.rapplogic.aru.core.Sketch;
import com.rapplogic.aru.core.SketchCore;

/**
 * Defines framework for uploading sketch to remote
 * 
 * @author andrew
 *
 */
public abstract class SketchUploader extends SketchCore {
	
	final Logger log = Logger.getLogger(SketchUploader.class);
	
	public final int MAGIC_BYTE1 = 0xef;
	public final int MAGIC_BYTE2 = 0xac;
	// make enum
	public final int CONTROL_PROG_REQUEST = 0x10;
	public final int CONTROL_WRITE_EEPROM = 0x20;
	// somewhat redundant
	public final int CONTROL_START_FLASH = 0x40;
	
	public final int OK = 1;
	public final int START_OVER = 2;
	public final int TIMEOUT = 3;
	
	public SketchUploader() {

	}
		
	public int[] getStartHeader(int sizeInBytes, int numPages, int bytesPerPage, int timeout) {
		return new int[] { 
				MAGIC_BYTE1, 
				MAGIC_BYTE2, 
				CONTROL_PROG_REQUEST, 
				10, //length of this header
				(sizeInBytes >> 8) & 0xff, 
				sizeInBytes & 0xff, 
				(numPages >> 8) & 0xff, 
				numPages & 0xff,
				bytesPerPage,
				timeout & 0xff				
		};
	}
	
	// TODO consider adding retry bit to header
	// TODO consider sending force reset bit to header
	
	// NOTE if header size is ever changed must also change PROG_DATA_OFFSET in library
	// xbee has error detection built-in but other protocols may need a checksum
	private int[] getHeader(int controlByte, int addressOrSize, int dataLength) {
		return new int[] {
				MAGIC_BYTE1, 
				MAGIC_BYTE2, 
				controlByte, 
				dataLength + 6, //length + 6 bytes for header
				(addressOrSize >> 8) & 0xff, 
				addressOrSize & 0xff
		};
	}

	public int[] getProgramPageHeader(int address16, int dataLength) {
		return getHeader(CONTROL_WRITE_EEPROM, address16, dataLength);
	}
	
	public int[] getFlashStartHeader(int progSize) {
		return getHeader(CONTROL_START_FLASH, progSize, 0);
	}	

	protected int[] combine(int[] a, int[] b) {
		int[] result = Arrays.copyOf(a, a.length + b.length);
		System.arraycopy(b, 0, result, a.length, b.length);
		return result;
	}

	protected abstract void open(Map<String,Object> context) throws Exception;
	protected abstract void writeData(int[] data, Map<String,Object> context) throws Exception;
	protected abstract void waitForAck(int timeout) throws NoAckException, Exception;
	protected abstract void close() throws Exception;
	protected abstract String getName();
	
	/**
	 * 
	 * @param file
	 * @param pageSize
	 * @param ackTimeout how long we wait for an ack before retrying
	 * @param arduinoTimeout how long before arduino resets after no activity
	 * @param verbose
	 * @param context
	 * @throws IOException
	 */
	public void process(String file, int pageSize, int ackTimeout, int arduinoTimeout, int retriesPerPacket, boolean verbose, Map<String,Object> context) throws IOException {
		// page size is max packet size for the radio
		Sketch sketch = parseSketchFromIntelHex(file, pageSize);
			
		context.put("verbose", verbose);
		
		try {
			open(context);
			
			long start = System.currentTimeMillis();
			int[] startHeader = getStartHeader(sketch.getSize(), sketch.getPages().size(), sketch.getBytesPerPage(), arduinoTimeout);
				
			// TODO create class for this
			for (int i = 0 ;i < retriesPerPacket; i++) {
				try {
					System.out.println("Sending sketch to " + getName() + " radio, size " + sketch.getSize() + " bytes, md5 " + getMd5(sketch.getProgram()) + ", number of packets " + sketch.getPages().size() + ", and " + sketch.getBytesPerPage() + " bytes per packet, header " + toHex(startHeader));			
					writeData(startHeader, context);
					waitForAck(ackTimeout);
					break;					
				} catch (NoAckException e) {
					System.out.println("Failed to deliver programming packet " + e.getMessage() + ".. retrying " + toHex(startHeader));
					
					if (i + 1 == retriesPerPacket) {
						throw new RuntimeException("Failed to send page after " + retriesPerPacket + " retries");
					}					
				}
			}

			
			for (Page page : sketch.getPages()) {				
				// make sure we exit on a kill signal like a good app
				if (Thread.currentThread().isInterrupted()) {
					throw new InterruptedException();
				}
				
				int[] data = combine(getProgramPageHeader(page.getRealAddress16(), page.getData().length), page.getData());
				
				for (int i = 0 ;i < retriesPerPacket; i++) {
					try {
						if (verbose) {
							System.out.println("Sending page " + (page.getOrdinal() + 1) + " of " + sketch.getPages().size() + ", with address " + page.getRealAddress16() + ", length " + data.length + ", packet " + toHex(data));
//							System.out.println("Data " + toHex(page.getData()));
						} else {
							System.out.print(".");
							
							if (page.getOrdinal() > 0 && page.getOrdinal() % 80 == 0) {
								System.out.println("");
							}
						}
						
						try {
							writeData(data, context);					
						} catch (Exception e) {
							throw new RuntimeException("Failed to deliver packet at page " + page.getOrdinal() + " of " + sketch.getPages().size(), e);
						}
						
						// don't send next page until this one is processed or we will overflow the buffer
						waitForAck(ackTimeout);		
						break;
					} catch (NoAckException e) {
						System.out.println("Failed to deliver programming packet " + e.getMessage() + ".. retrying " + toHex(data));
						
						if (i + 1 == retriesPerPacket) {
							throw new RuntimeException("Failed to send page after " + retriesPerPacket + " retries");
						}
					}
				}
			}

			if (!verbose) {
				System.out.println("");
			}

			System.out.println("Sending flash start packet " + toHex(getFlashStartHeader(sketch.getSize())));
			
			int[] flash = getFlashStartHeader(sketch.getSize());
			
			for (int i = 0 ;i < retriesPerPacket; i++) {
				try {
					writeData(flash, context);
					waitForAck(ackTimeout);	
					break;
				} catch (NoAckException e) {
					System.out.println("Failed to deliver flash packet.. retrying" + toHex(flash));
					
					if (i + 1 == retriesPerPacket) {
						throw new RuntimeException("Failed to send flash packet after " + retriesPerPacket + " retries");
					}					
				}
			}

			System.out.println("Successfully flashed remote Arduino in " + (System.currentTimeMillis() - start) + "ms");
		} catch (InterruptedException e) {
			// kill signal
			System.out.println("Interrupted during programming.. exiting");
			return;
		} catch (Exception e) {
			log.error("Unexpected error", e);
		} finally {
			try {
				close();
			} catch (Exception e) {}
		}
	}
	
	public static class NoAckException extends Exception {
		public NoAckException(String arg0) {
			super(arg0);
		}
	}
}
