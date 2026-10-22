import java.util.*;

public class MixTest {
    static final char[] B58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".toCharArray();

    static String enc(long n) {
        StringBuilder sb = new StringBuilder(12);
        do {
            sb.append(B58[(int) Long.remainderUnsigned(n, 58L)]);
            n = Long.divideUnsigned(n, 58L);
        } while (n != 0L);
        return sb.reverse().toString();
    }

    static long mix(long x) {
        long z = x + 0x9E3779B97F4C7A15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    public static void main(String[] args) {
        long K = 0x9E3779B97FL, MASK = (1L << 40) - 1;

        // 1) 乘法逆元混淆：相邻 id 的 codeInt 是否等差？
        long c1 = (1 * K) & MASK, c2 = (2 * K) & MASK, c3 = (3 * K) & MASK, c4 = (4 * K) & MASK;
        System.out.println("[1] mul deltas (should be identical if arithmetic): "
                + (c2 - c1) + " " + (c3 - c2) + " " + (c4 - c3));

        // 2) 7 字符等长码占比（字典序可枚举性的前提）
        long p58_6 = 1;
        for (int i = 0; i < 6; i++) p58_6 *= 58;
        System.out.printf("[2] P(len==7)=%.4f%n", 1.0 - (double) p58_6 / Math.pow(2, 40));

        // 3) 等长子集内：字典序升序 == id 升序 ?
        List<String> codes = new ArrayList<>();
        for (long id = 1; id <= 200; id++) codes.add(enc((id * K) & MASK));
        List<String> sorted = new ArrayList<>(codes);
        Collections.sort(sorted);
        System.out.println("[3] MUL lexOrder==idOrder : " + codes.equals(sorted));

        // 4) 变长：是否会出现 <2 字符短码（Gateway DB_CODE 正则有下限 2）
        int min = 99, max = 0;
        long shortCount = 0;
        for (long id = 1; id <= 2_000_000; id++) {
            String s = enc((id * K) & MASK);
            min = Math.min(min, s.length());
            max = Math.max(max, s.length());
            if (s.length() < 2) shortCount++;
        }
        System.out.println("[4] MUL len range=" + min + ".." + max + "  (<2 chars: " + shortCount + "/2000000)");

        // 5) splitmix64：相邻 id 的输出差值
        System.out.println("[5] splitmix deltas: " + (mix(2) - mix(1)) + " " + (mix(3) - mix(2)));

        // 6) splitmix64 在 64 位域是否单射（双射性）
        HashSet<Long> seen = new HashSet<>();
        boolean inj = true;
        for (long id = 1; id <= 2_000_000; id++) if (!seen.add(mix(id))) { inj = false; break; }
        System.out.println("[6] splitmix injective over 2e6 samples: " + inj);

        // 7) splitmix64 后字典序 vs id 序
        List<String> sc = new ArrayList<>();
        for (long id = 1; id <= 200; id++) sc.add(enc(mix(id)));
        List<String> ss = new ArrayList<>(sc);
        Collections.sort(ss);
        System.out.println("[7] SPLITMIX lexOrder==idOrder : " + sc.equals(ss));

        // 8) splitmix64 短码长度范围（未补齐时）
        int mn = 99, mx = 0;
        for (long id = 1; id <= 2_000_000; id++) {
            int L = enc(mix(id)).length();
            mn = Math.min(mn, L);
            mx = Math.max(mx, L);
        }
        System.out.println("[8] SPLITMIX len range (no padding) = " + mn + ".." + mx);
    }
}
