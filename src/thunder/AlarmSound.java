package thunder;

import haven.*;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.*;

/**
 * Alarm clips loaded from plain wav files, Hurricane-style: a default is
 * bundled in the jar under /AlarmSounds/, and users can replace it by dropping
 * their own {@code AlarmSounds/<name>.wav} next to the client (Config.HOMEDIR).
 * Any wav javax.sound can read works; it is converted to the mixer's format
 * (44.1kHz 16-bit stereo) at load time.
 */
public class AlarmSound {
    private static final AudioFormat TARGET =
	new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 44100, 16, 2, 4, 44100, false);

    /** Decoded PCM is cached by the caller via the returned clip; each play gets a fresh stream. */
    public static Audio.Clip load(String name, Audio.Clip fallback) {
	byte[] pcm = readPcm("AlarmSounds/" + name + ".wav");
	if(pcm == null) {return fallback;}
	return () -> new Audio.PCMClip(new ByteArrayInputStream(pcm), 2, Audio.PCMClip.SN16);
    }

    private static byte[] readPcm(String path) {
	try(InputStream in = open(path)) {
	    if(in == null) {return null;}
	    AudioInputStream wav = AudioSystem.getAudioInputStream(new BufferedInputStream(in));
	    try(AudioInputStream pcm = AudioSystem.getAudioInputStream(TARGET, wav)) {
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		byte[] tmp = new byte[8192];
		for(int n; (n = pcm.read(tmp)) > 0; ) {buf.write(tmp, 0, n);}
		return buf.toByteArray();
	    }
	} catch(Exception e) {
	    return null;
	}
    }

    private static InputStream open(String path) throws IOException {
	File f = Config.getFile(path);
	if(f.exists() && f.canRead()) {return new FileInputStream(f);}
	return AlarmSound.class.getResourceAsStream("/" + path);
    }
}
