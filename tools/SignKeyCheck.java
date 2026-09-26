/*
 * ECDSA signatures in P1363 form (r || s, each the width of the curve's field), both ways.
 *
 * A friend crashed on 2026-09-26 pressing the in-game "visit store" button: SteamStore signs a
 * request with the session key (JWK ES256), and SignKey.ECDSA.int2ext turns Java's DER signature
 * into P1363. It took r and s only at exactly 32 bytes (or 33 with DER's sign byte), but DER drops
 * a number's leading zero bytes, so one signature in about 128 has a 31-byte half and the button
 * threw "unexpected number length 31 (expected 32)". The same code sized the halves by the HASH,
 * which is not the field for P-521 (66 bytes, SHA-512 is 64), and the reverse conversion left
 * leading zeros in its DER integers.
 *
 * The control is the JDK's own P1363 implementation ("SHA256withECDSAinP1363Format" and kin):
 * every signature we make must verify under it, and every one it makes must verify under ours.
 *
 * NOT part of the client build. Run from the repo root (PowerShell), after `ant jar`:
 *
 *   $CP="build\classes;build\classes-lib;lib\*"
 *   javac -nowarn -cp $CP -d $env:TEMP\signcheck tools\SignKeyCheck.java
 *   java -cp "$env:TEMP\signcheck;$CP" haven.SignKeyCheck
 *
 * Exits 0 when every check passes, 1 otherwise.
 */
package haven;

import java.security.Signature;
import java.util.Random;

public class SignKeyCheck {
    static int fails = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if(!ok)
            fails++;
    }

    static void run(String name, SignKey.Algorithm alg, String jdk, int width, int n) throws Exception {
        SignKey.ECDSA key = (SignKey.ECDSA)alg.generate();
        Random r = new Random(name.hashCode());
        int threw = 0, badlen = 0, oursRejected = 0, jdkRejected = 0, jdkSigsRejected = 0;
        String first = null;
        for(int i = 0; i < n; i++) {
            byte[] msg = new byte[1 + r.nextInt(64)];
            r.nextBytes(msg);
            byte[] sig;
            try {
                sig = key.sign(msg);
            } catch(RuntimeException e) {
                threw++;
                if(first == null)
                    first = e.toString();
                continue;
            }
            if(sig.length != 2 * width)
                badlen++;
            if(!key.verify(sig, msg))
                oursRejected++;
            Signature v = Signature.getInstance(jdk);
            v.initVerify(key.pub);
            v.update(msg);
            if(!v.verify(sig))
                jdkRejected++;
            /* and the other way: a P1363 signature the JDK made, through our verifier */
            Signature s = Signature.getInstance(jdk);
            s.initSign(key.prv);
            s.update(msg);
            if(!key.verify(s.sign(), msg))
                jdkSigsRejected++;
        }
        System.out.printf("  %s: %d signatures%n", name, n);
        check(threw == 0, name + " signing never throws (" + threw + (first == null ? "" : ", e.g. " + first) + ")");
        check(badlen == 0, name + " signatures are " + (2 * width) + " bytes (" + badlen + " were not)");
        check(oursRejected == 0, name + " our signatures verify with our verifier (" + oursRejected + " did not)");
        check(jdkRejected == 0, name + " our signatures verify with the JDK's P1363 verifier (" + jdkRejected + " did not)");
        check(jdkSigsRejected == 0, name + " the JDK's P1363 signatures verify with ours (" + jdkSigsRejected + " did not)");
    }

    public static void main(String[] args) throws Exception {
        int n = (args.length > 0) ? Integer.parseInt(args[0]) : 3000;
        run("ES256", SignKey.JWK.ES256, "SHA256withECDSAinP1363Format", 32, n);
        run("ES384", SignKey.JWK.ES384, "SHA384withECDSAinP1363Format", 48, n);
        run("ES512", SignKey.JWK.ES512, "SHA512withECDSAinP1363Format", 66, n);
        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
