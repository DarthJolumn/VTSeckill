import java.util.*;

public class UnmixTest {
    static final char[] B58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".toCharArray();
    static final int MIN_LENGTH = 6;
    static final long SEED = 0x9E3779B97F4C7A15L;

    static long mix(long id) {
        long z = id + SEED;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    static long unxorshift(long y, int k) {
        long x = y;
        for (int i = 0; i < Long.SIZE / k + 1; i++) x = y ^ (x >>> k);
        return x;
    }

    static long modInverse64(long odd) {
        long inv = odd;
        for (int i = 0; i < 6; i++) inv *= 2 - odd * inv;
        return inv;
    }

    static long unmultiply(long y, long odd) { return y * modInverse64(odd); }

    static long unmix(long z) {
        z = unxorshift(z, 31);
        z = unmultiply(z, 0x94D049BB133111EBL);
        z = unxorshift(z, 27);
        z = unmultiply(z, 0xBF58476D1CE4E5B9L);
        z = unxorshift(z, 30);
        return z - SEED;
    }

    static String encode(long id) {
        long n = mix(id);
        StringBuilder sb = new StringBuilder(12);
        do {
            sb.append(B58[(int) Long.remainderUnsigned(n, 58L)]);
            n = Long.divideUnsigned(n, 58L);
        } while (n != 0L);
        while (sb.length() < MIN_LENGTH) sb.append(B58[0]);
        return sb.reverse().toString();
    }

    static Optional<Long> decode(String code) {
        long n = 0L;
        for (int i = 0; i < code.length(); i++) {
            int d = indexOf(code.charAt(i));
            if (d < 0) return Optional.empty();
            n = n * 58L + d;
        }
        return Optional.of(unmix(n));
    }

    static int indexOf(char c) {
        for (int i = 0; i < B58.length; i++) if (B58[i] == c) return i;
        return -1;
    }

    public static void main(String[] args) {
        // 1) unmix 正确性
        long bad = 0;
        for (long id = -1000; id <= 1000; id++) if (unmix(mix(id)) != id) bad++;
        System.out.println("[1] unmix(mix(id))==id over 2001 samples, failures=" + bad);

        // 2) 随机大 id round-trip
        Random r = new Random(42);
        int fail2 = 0;
        for (int i = 0; i < 200000; i++) {
            long id = r.nextLong();
            if (unmix(mix(id)) != id) fail2++;
        }
        System.out.println("[2] random 64-bit round-trip failures=" + fail2 + "/200000");

        // 3) encode/decode round-trip + 长度范围
        int min = 99, max = 0, fail3 = 0;
        Set<String> seen = new HashSet<>();
        for (long id = 1; id <= 2_000_000; id++) {
            String s = encode(id);
            min = Math.min(min, s.length());
            max = Math.max(max, s.length());
            if (decode(s).orElse(-1L) != id) fail3++;
            if (!seen.add(s)) { fail3++; break; }
        }
        System.out.println("[3] encode/decode+unique: len=" + min + ".." + max + " failures=" + fail3);

        // 4) 相邻 id 的前缀相同概率（avalanche 佐证）
        int same4 = 0;
        for (long id = 1; id <= 100000; id++) {
            String a = encode(id), b = encode(id + 1);
            if (a.length() >= 4 && b.length() >= 4 && a.substring(0, 4).equals(b.substring(0, 4))) same4++;
        }
        System.out.println("[4] adjacent ids sharing first 4 chars: " + same4 + "/100000");

        // 5) 负数/0 id 也要能 round-trip（保险）
        System.out.println("[5] mix(0)->" + encode(0) + " decode=" + decode(encode(0)).get());

        // 6) 非法字符
        System.out.println("[6] decode(\"0OIl\") = " + decode("0OIl"));
    }
}
