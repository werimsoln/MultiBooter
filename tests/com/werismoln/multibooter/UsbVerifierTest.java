package com.werismoln.multibooter;
import java.io.*; import java.util.*;
public class UsbVerifierTest {
  static int fails=0;
  static void check(boolean c,String m){ System.out.println((c?"PASS ":"FAIL ")+m); if(!c) fails++; }
  static class Dev implements UsbVerifier.BlockReader {
    byte[] data; int reads=0; long flipOnceAt=-1;
    Dev(byte[] d){data=d;}
    public void read(long lba, byte[] out, int sectors) throws IOException { reads++; System.arraycopy(data,(int)(lba*512),out,0,sectors*512);
      if(flipOnceAt>=0){ long s=lba*512; if(flipOnceAt>=s && flipOnceAt<s+sectors*512L){ out[(int)(flipOnceAt-s)]^=0x55; flipOnceAt=-1;} } }
  }
  static UsbVerifier.Result run(byte[] src, byte[] devData, UsbVerifier.Mode mode, Dev dev) throws Exception {
    UsbVerifier.Recorder rec=new UsbVerifier.Recorder(src.length,mode);
    // feed in odd-sized chunks like a real write loop (64 KiB + remainder)
    for(int off=0; off<src.length; ){ int n=Math.min(65536*3+17, src.length-off); rec.update(src,off,n); off+=n; }
    final byte[] s=src;
    return UsbVerifier.verify(rec, dev, 512, ()->new ByteArrayInputStream(s), new UsbVerifier.Progress(){ public void onProgress(long d,long t){} public boolean cancelled(){return false;} });
  }
  public static void main(String[] a) throws Exception {
    Random r=new Random(42);
    int size=5*1024*1024+1234; // not a multiple of 1 MiB nor 512
    byte[] src=new byte[size]; r.nextBytes(src);
    byte[] disk=new byte[((size+511)/512)*512]; System.arraycopy(src,0,disk,0,size);
    // 1. good write
    UsbVerifier.Result res=run(src,disk,UsbVerifier.Mode.FULL,new Dev(disk));
    check(res.ok && res.sha256.length()==64, "full verify of a good write ("+res.message.substring(0,30)+"...)");
    java.security.MessageDigest md=java.security.MessageDigest.getInstance("SHA-256"); String want=UsbVerifier.hex(md.digest(src));
    check(res.sha256.equals(want),"reported SHA-256 equals sha256(source)");
    // 2. one flipped byte in the middle
    byte[] bad=disk.clone(); int pos=3*1024*1024+777; bad[pos]^=1;
    res=run(src,bad,UsbVerifier.Mode.FULL,new Dev(bad));
    check(!res.ok && res.failOffset==pos && res.failLba==pos/512 && res.failByteInSector==pos%512, "full: exact offset "+res.failOffset+" LBA "+res.failLba+" byte "+res.failByteInSector+" (expected "+pos+")");
    check(res.message.contains("drive holds different data"),"message says the drive holds different data");
    // 3. corruption in the very last (partial) block, last byte of the image
    bad=disk.clone(); pos=size-1; bad[pos]^=0x80;
    res=run(src,bad,UsbVerifier.Mode.FULL,new Dev(bad));
    check(!res.ok && res.failOffset==pos,"full: corruption in last byte of the image found at "+res.failOffset);
    // 4. padding bytes after the image are not compared
    bad=disk.clone(); bad[size+10]^=0xFF;
    res=run(src,bad,UsbVerifier.Mode.FULL,new Dev(bad));
    check(res.ok,"changes in the zero padding after the image are ignored");
    // 5. flaky read (disk fine, one read returns a flipped byte)
    Dev flaky=new Dev(disk); flaky.flipOnceAt=2*1024*1024+5;
    res=run(src,disk,UsbVerifier.Mode.FULL,flaky);
    check(!res.ok && res.message.contains("unreliable"),"flaky read is reported as unreliable drive/cable");
    // 6. read error / disconnect
    UsbVerifier.BlockReader dead=(lba,out,s)->{ throw new IOException("USB device disconnected"); };
    res=run(src,disk,UsbVerifier.Mode.FULL,null==null?new Dev(disk){ public void read(long l,byte[] o,int s) throws IOException { if(reads++>3) throw new IOException("USB device disconnected"); super.read(l,o,s);} }:null);
    check(!res.ok && res.message.contains("could not be read back"),"read failure/disconnect fails the verification");
    // 7. partial mode on a big image: corruption in a non-sampled block is missed, in a sampled one found
    int big=200*1024*1024; byte[] bsrc=new byte[big]; r.nextBytes(bsrc);
    byte[] bdisk=bsrc.clone();
    boolean[] sel=UsbVerifier.select(big,UsbVerifier.Mode.PARTIAL);
    int unsel=-1,samp=-1; for(int i=70;i<sel.length-70;i++){ if(!sel[i]&&unsel<0) unsel=i; if(sel[i]&&samp<0) samp=i; }
    int selCount=0; for(boolean b:sel) if(b) selCount++;
    check(selCount==64*2+32,"partial mode checks "+selCount+" of "+sel.length+" blocks ("+UsbVerifier.bytesToVerify(big,UsbVerifier.Mode.PARTIAL)/1048576+" MiB)");
    byte[] b1=bdisk.clone(); b1[unsel*1048576+9]^=1;
    res=run(bsrc,b1,UsbVerifier.Mode.PARTIAL,new Dev(b1));
    check(res.ok,"partial: corruption in an unchecked block is NOT detected (expected trade-off)");
    byte[] b2=bdisk.clone(); b2[samp*1048576+9]^=1;
    res=run(bsrc,b2,UsbVerifier.Mode.PARTIAL,new Dev(b2));
    check(!res.ok&&res.failOffset==(long)samp*1048576+9,"partial: corruption in a sampled block found at exact offset");
    byte[] b3=bdisk.clone(); b3[10]^=1;
    res=run(bsrc,b3,UsbVerifier.Mode.PARTIAL,new Dev(b3));
    check(!res.ok&&res.failOffset==10,"partial: corruption at the start is found");
    // 8. cancel
    UsbVerifier.Recorder rec=new UsbVerifier.Recorder(src.length,UsbVerifier.Mode.FULL); rec.update(src,0,src.length);
    res=UsbVerifier.verify(rec,new Dev(disk),512,()->new ByteArrayInputStream(src),new UsbVerifier.Progress(){public void onProgress(long d,long t){} public boolean cancelled(){return true;}});
    check(res.cancelled && !res.ok,"cancel stops the verification");
    System.out.println(fails==0?"ALL OK":"FAILURES: "+fails); System.exit(fails==0?0:1);
  }
}
