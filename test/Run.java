import java.io.*;
import java.util.*;
import com.cburch.logisim.file.*;
import com.cburch.logisim.circuit.*;
import com.cburch.logisim.comp.*;
import com.cburch.logisim.instance.*;
import com.cburch.logisim.data.*;
import com.cburch.logisim.std.wiring.*;

/**
 * 把整机跑到停（PC 不再前进），报告：
 *   - 总周期数（= 执行的指令条数，单周期 CPU 一指令一周期）
 *   - 每条指令的 PC / IR / MemWrite / RegWrite
 *   - 最终 LedData
 *
 *   java Run <circ> <电路名> [maxTicks]
 */
public class Run {
    static Pin PIN = new Pin();

    public static void main(String[] a) throws Exception {
        Loader ld = new Loader(null);
        LogisimFile lf = ld.openLogisimFile(new File(a[0]));
        Circuit S = null;
        for (Circuit c : lf.getCircuits()) if (c.getName().equals(a[1])) S = c;

        Map<String, Component> pins = new LinkedHashMap<>();
        List<Component> clocks = new ArrayList<>();
        for (Component c : S.getNonWires()) {
            String nm = c.getFactory().getName();
            if (nm.equals("Clock")) clocks.add(c);
            if (!nm.equals("Pin")) continue;
            AttributeSet as = c.getAttributeSet();
            for (Attribute<?> at : as.getAttributes())
                if (at.getName().equals("label"))
                    pins.put(String.valueOf(as.getValue((Attribute) at)), c);
        }

        int max = a.length > 2 ? Integer.parseInt(a[2]) : 4000;

        com.cburch.logisim.proj.Project proj = new com.cburch.logisim.proj.Project(lf);
        CircuitState cs = new CircuitState(proj, S);
        cs.getPropagator().propagate();

        String[] show = {"PC", "IR", "RegWrite", "MemWrite", "RDin", "MDin", "LedData"};

        String lastPc = null;
        int sameRun = 0, cycles = 0, t = 0;
        System.out.println("周期  " + String.join("  ", pad(show)));
        System.out.println("--------------------------------------------------------------");

        // high=1 low=1 -> 2 tick 一个周期，偶数 tick 时 clk=0，PC 已稳定
        for (t = 1; t <= max; t++) {
            for (Component ck : clocks) Clock.tick(cs, t, ck);
            cs.getPropagator().propagate();
            if (t % 2 != 0) continue;         // 只在整周期采样

            String pc = val(cs, pins.get("PC"));
            String[] v = new String[show.length];
            for (int i = 0; i < show.length; i++) v[i] = val(cs, pins.get(show[i]));

            if (pc.equals(lastPc)) {
                sameRun++;
            } else {
                sameRun = 0;
                cycles++;
                System.out.println(String.format("%-5d %s", cycles, String.join("  ", padN(v))));
            }
            lastPc = pc;

            // PC 连续 4 个周期不动 -> 认定停机
            if (sameRun >= 4) break;
        }

        System.out.println("--------------------------------------------------------------");
        System.out.println("总周期数(指令数) = " + cycles);
        System.out.println("停在 PC          = " + val(cs, pins.get("PC")));
        System.out.println("LedData          = " + val(cs, pins.get("LedData")));
        System.exit(0);
    }

    static String val(CircuitState cs, Component c) {
        if (c == null) return "-";
        Value v = cs.getInstanceState(c).getPort(0);
        return v == null ? "null" : v.toDisplayString(16);
    }

    static String[] pad(String[] s) { return padN(s); }

    static String[] padN(String[] s) {
        String[] r = new String[s.length];
        for (int i = 0; i < s.length; i++)
            r[i] = String.format("%-10s", s[i] == null ? "-" : s[i]);
        return r;
    }
}
