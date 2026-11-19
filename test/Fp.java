import java.util.*;
import java.io.*;
import com.cburch.logisim.file.*;
import com.cburch.logisim.circuit.*;
import com.cburch.logisim.comp.*;
import com.cburch.logisim.instance.*;
import com.cburch.logisim.data.*;
import com.cburch.logisim.std.wiring.*;

/**
 * 功能指纹：把电路里每个子电路的全部输入-输出关系打出来，存成文本。
 *
 * 用途：改版式（挪元件、重排线）时，功能必须一点不变。
 * 改前存一份指纹，改后再存一份，diff 完全一致就说明纯属"动了外观"。
 *
 *   java Fp <circ> <out.txt>
 *
 * 策略：
 *   输入总位宽 <= 12  -> 穷举 2^w 组（NPC 5 位、EXT 17 位too big... 见下）
 *   否则              -> 固定种子抽 256 组伪随机（可复现）
 * 固定种子是关键：同一个文件跑多少次结果都一样，才能拿来 diff。
 */
public class Fp {

    static Pin PIN = new Pin();

    static int maskInt(int w) {
        return w >= 32 ? -1 : ((1 << w) - 1);
    }

    static class Port {
        String name; Component comp; int width; boolean out;
    }

    public static void main(String[] a) throws Exception {
        Loader ld = new Loader(null);
        LogisimFile lf = ld.openLogisimFile(new File(a[0]));
        PrintStream out = new PrintStream(
                new FileOutputStream(a[1]), false, "UTF-8");

        com.cburch.logisim.proj.Project proj =
                new com.cburch.logisim.proj.Project(lf);

        for (Circuit S : lf.getCircuits()) {
            List<Port> ps = new ArrayList<>();
            for (Component c : S.getNonWires()) {
                if (!c.getFactory().getName().equals("Pin")) continue;
                Port p = new Port();
                AttributeSet as = c.getAttributeSet();
                for (Attribute<?> at : as.getAttributes()) {
                    String n = at.getName();
                    if (n.equals("label"))
                        p.name = String.valueOf(as.getValue((Attribute) at));
                    if (n.equals("width"))
                        p.width = Integer.parseInt(
                                String.valueOf(as.getValue((Attribute) at)));
                    if (n.equals("output"))
                        p.out = "true".equals(
                                String.valueOf(as.getValue((Attribute) at)));
                }
                p.comp = c;
                ps.add(p);
            }
            if (ps.isEmpty()) continue;
            ps.sort(Comparator.comparing(p -> p.name));

            List<Port> ins = new ArrayList<>(), outs = new ArrayList<>();
            for (Port p : ps) (p.out ? outs : ins).add(p);

            int w = 0;
            for (Port p : ins) w += p.width;
            boolean exh = (w <= 12);
            int n = exh ? (1 << w) : 256;

            out.println("# " + S.getName() + "  ins=" + ins.size()
                        + " outs=" + outs.size() + " vectors=" + n
                        + (exh ? " (exhaustive)" : " (random)"));

            Random rnd = new Random(20261119L);

            for (int k = 0; k < n; k++) {
                // ⚠ 必须每组输入重建 CircuitState。
                //   复用同一个 cs 反复 setValue + propagate 时，只有第一次生效，
                //   之后输出恒等于第一组的结果（propagator 的脏标记复用有问题）。
                CircuitState cs = new CircuitState(proj, S);

                int rest = exh ? k : 0;
                StringBuilder sb = new StringBuilder(S.getName()).append(" |");
                for (Port p : ins) {
                    int val = exh
                            ? (rest & maskInt(p.width))
                            : (rnd.nextInt() & maskInt(p.width));
                    if (exh) rest = (p.width >= 32) ? 0 : (rest >>> p.width);
                    PIN.setValue(cs.getInstanceState(p.comp),
                            Value.createKnown(BitWidth.create(p.width),
                                    val & maskInt(p.width)));
                    sb.append(' ').append(p.name).append('=')
                      .append(Integer.toHexString(val));
                }
                cs.getPropagator().propagate();
                sb.append(" ->");
                for (Port p : outs)
                    sb.append(' ').append(p.name).append('=')
                      .append(PIN.getValue(cs.getInstanceState(p.comp))
                                  .toDisplayString(16));
                out.println(sb);
            }
        }
        out.close();
        System.out.println("ok " + a[1]);
        System.exit(0);
    }
}
