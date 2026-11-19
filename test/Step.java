import java.io.*;
import java.util.*;
import com.cburch.logisim.file.*;
import com.cburch.logisim.circuit.*;
import com.cburch.logisim.comp.*;
import com.cburch.logisim.instance.*;
import com.cburch.logisim.data.*;
import com.cburch.logisim.std.wiring.*;

/**
 * 用 Logisim 引擎推进时钟，真正跑几条指令。
 *
 *   java Step <circ> <电路名> <tick 次数>
 *
 * 时钟由 Clock.tick(CircuitState, int, Component) 静态方法驱动
 * —— 这是 Logisim 内部给时钟元件用的入口，不是模拟，是真跑。
 */
public class Step {
    static Pin PIN = new Pin();

    public static void main(String[] a) throws Exception {
        Loader ld = new Loader(null);
        LogisimFile lf = ld.openLogisimFile(new File(a[0]));
        Circuit S = null;
        for (Circuit c : lf.getCircuits()) if (c.getName().equals(a[1])) S = c;

        com.cburch.logisim.proj.Project proj = new com.cburch.logisim.proj.Project(lf);
        CircuitState cs = new CircuitState(proj, S);

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
        System.out.println("时钟元件: " + clocks.size());
        for (int i = 0; i < clocks.size(); i++) {
            Component ck = clocks.get(i);
            System.out.println("   #" + i + " @ " + ck.getLocation()
                + "  high=" + ck.getAttributeSet().getValue(Clock.ATTR_HIGH)
                + " low=" + ck.getAttributeSet().getValue(Clock.ATTR_LOW));
        }
        System.out.println("端口: " + pins.keySet());

        int n = a.length > 2 ? Integer.parseInt(a[2]) : 20;
        int which = a.length > 3 ? Integer.parseInt(a[3]) : -1;
        // 注意：S.getNonWires() 是 Set，遍历顺序每次 JVM 都可能不同，
        // 按索引挑「主时钟」并不可靠。默认 -1 = 全部时钟一起喂同一个 tick 序号。
        // 用 Collections.singletonList 而不是 List.of：后者是 Java 9+ 的 API
        List<Component> targets = (which < 0) ? clocks : Collections.singletonList(clocks.get(which));
        if (which >= 0) System.out.println("只驱动 #" + which + " @ " + clocks.get(which).getLocation());

        String[] show = {"PC", "IR", "RDin", "RegWrite", "MemWrite", "MDin", "LedData"};
        String hdr = String.format("%-6s%-6s", "tick", "clk");
        for (String s : show) hdr += String.format("%-12s", s);
        System.out.println(hdr);

        // 先跑一次组合传播，让第一拍的信号稳定下来
        cs.getPropagator().propagate();
        for (int t = 0; t <= n; t++) {
            if (t > 0) {
                // 第二参数是「绝对 tick 序号」，不是增量：
                // 内部按 count % (high+low) < low 决定电平，传常数值不会产生方波。
                for (Component ck : targets) Clock.tick(cs, t, ck);
                cs.getPropagator().propagate();
            }
            Value ckv = cs.getInstanceState(targets.get(0)).getPort(0);
            String clk = (ckv == null) ? "null" : ckv.toDisplayString();
            StringBuilder sb = new StringBuilder(String.format("%-6d%-6s", t, clk));
            for (String s : show) {
                Component p = pins.get(s);
                if (p == null) { sb.append(String.format("%-12s", "-")); continue; }
                Value v = cs.getInstanceState(p).getPort(0);
                sb.append(String.format("%-12s", v == null ? "null" : v.toDisplayString(16)));
            }
            System.out.println(sb);
        }
        System.exit(0);
    }
}
