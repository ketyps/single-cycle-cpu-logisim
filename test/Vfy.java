import java.lang.reflect.*;
import java.io.*;
import java.util.*;
import com.cburch.logisim.file.*;
import com.cburch.logisim.circuit.*;
import com.cburch.logisim.comp.*;
import com.cburch.logisim.instance.*;
import com.cburch.logisim.data.*;
import com.cburch.logisim.tools.*;
import com.cburch.logisim.std.wiring.*;

/**
 * 用 Logisim 引擎做真值表验证。
 *
 * 关键：不读 Pin 的坐标，直接走 Instance 层的 getPort(i)。
 * Pin 只有一个端口（#0），Instance 的 location 就是 Pin 的 loc，
 * 所以 cs.getInstanceState(pinInstance) 即该端口的 InstanceState。
 *
 *   java Vfy <circ> <sub> in=hex ExtOp=0 ...
 *   java Vfy <circ> <sub>                 -> 穷举（组合数 <= 65536）
 */
public class Vfy {

    static Pin PIN = new Pin();

    /** Value.createKnown 不截断：宽度小于 32 时高位会污染扩展，必须显式掩码 */
    static int mask(int v, int w) {
        return w >= 32 ? v : (v & ((1 << w) - 1));
    }

    static class Port { String name; Component comp; int width; boolean out; }

    public static void main(String[] a) throws Exception {
        Loader ld = new Loader(null);
        LogisimFile lf = ld.openLogisimFile(new File(a[0]));
        String sub = a[1];
        Circuit S = null;
        for (Circuit c : lf.getCircuits()) if (c.getName().equals(sub)) S = c;
        if (S == null) throw new RuntimeException("no " + sub);

        List<Port> ports = new ArrayList<>();
        for (Component c : S.getNonWires()) {
            if (!c.getFactory().getName().equals("Pin")) continue;
            Port p = new Port();
            AttributeSet as = c.getAttributeSet();
            for (Attribute<?> at : as.getAttributes()) {
                String n = at.getName();
                if (n.equals("label"))  p.name  = String.valueOf(as.getValue((Attribute) at));
                if (n.equals("width"))  p.width = Integer.parseInt(String.valueOf(as.getValue((Attribute) at)));
                if (n.equals("output")) p.out   = "true".equals(String.valueOf(as.getValue((Attribute) at)));
            }
            p.comp = c;
            ports.add(p);
        }
        ports.sort(Comparator.comparing(p -> p.name));

        com.cburch.logisim.proj.Project proj = new com.cburch.logisim.proj.Project(lf);
        CircuitState cs = new CircuitState(proj, S);

        // 不用 stream().toList()：那是 Java 16+ 的 API，会让 JDK 8/11 无法编译。
        List<Port> ins = new ArrayList<Port>();
        List<Port> outs = new ArrayList<Port>();
        for (Port p : ports) {
            if (p.out) outs.add(p); else ins.add(p);
        }
        System.out.print("IN: ");
        for (Port p : ins) System.out.print(p.name + "[" + p.width + "] ");
        System.out.print(" OUT: ");
        for (Port p : outs) System.out.print(p.name + "[" + p.width + "] ");
        System.out.println();

        if (a.length > 2) {
            for (int i = 2; i < a.length; i++) {
                String[] kv = a[i].split("=");
                for (Port p : ins) if (p.name.equals(kv[0]))
                    PIN.setValue(cs.getInstanceState(p.comp),
                        Value.createKnown(BitWidth.create(p.width),
                            mask((int) Long.parseLong(kv[1], 16), p.width)));
            }
            cs.getPropagator().propagate();
            StringBuilder sb = new StringBuilder("   ");
            for (Port p : outs)
                sb.append(p.name).append(" = ")
                  .append(PIN.getValue(cs.getInstanceState(p.comp)).toDisplayString(16)).append("   ");
            System.out.println(sb);
            System.exit(0);
        }

        int total = 1;
        for (Port p : ins) total *= (1 << p.width);
        if (total > (1 << 16)) { System.out.println("too many: " + total); return; }
        for (int k = 0; k < total; k++) {
            // ⚠ 每组输入必须重建 CircuitState。
            //   复用同一个 cs 时，只有第一组生效，之后输出恒等于第一组
            //   （propagator 的脏标记在复用下失效）。Fp 里踩过同一个坑。
            CircuitState cs2 = new CircuitState(proj, S);
            int rest = k;
            StringBuilder sb = new StringBuilder("  ");
            for (Port p : ins) {
                int v = rest & ((1 << p.width) - 1);
                rest >>= p.width;
                PIN.setValue(cs2.getInstanceState(p.comp),
                    Value.createKnown(BitWidth.create(p.width), mask(v, p.width)));
                sb.append(String.format("%s=%X ", p.name, v));
            }
            cs2.getPropagator().propagate();
            sb.append("-> ");
            for (Port p : outs)
                sb.append(p.name).append("=")
                  .append(PIN.getValue(cs2.getInstanceState(p.comp)).toDisplayString(16)).append(" ");
            System.out.println(sb);
        }
        System.exit(0);
    }
}
