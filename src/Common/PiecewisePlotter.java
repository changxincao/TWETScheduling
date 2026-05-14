package Common;
import javax.imageio.ImageIO;
import javax.swing.*;


import java.awt.*;
import java.awt.geom.Line2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class PiecewisePlotter {


    /**
     * 将多条分段线性函数绘制到同一坐标系并保存为 PNG。
     * @param functions 分段线性函数列表
     */
    public static void plotAndSave(List<PiecewiseLinearFunction> functions) {
        final int WIDTH  = 1600;
        final int HEIGHT = 1200;
        final String OUTPUT = "piecewise_plot.png";

        // 自动生成名称和配色
        List<String> names  = new ArrayList<>();
        List<Color>  colors = new ArrayList<>();
        int n = functions.size();
        for (int i = 0; i < n; i++) {
            names.add("f" + (i + 1));
            float hue = i / (float)n;
            colors.add(Color.getHSBColor(hue, 0.7f, 0.7f));
        }

        // 初始化绘图面板
        PlotPanel panel = new PlotPanel(functions, names, colors);
        panel.setSize(WIDTH, HEIGHT);

        // 渲染到 BufferedImage
        BufferedImage img = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        panel.paint(g2);
        g2.dispose();

        // 保存为文件
        try {
            ImageIO.write(img, "png", new File(OUTPUT));
            System.out.println("Plot saved to " + OUTPUT);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static class PlotPanel extends JPanel {
        private final List<PiecewiseLinearFunction> functions;
        private final List<String> names;
        private final List<Color> colors;
        private static final int MARGIN = 50;
        private static final double GRID_STEP_X = 1.0;
        private static final double GRID_STEP_Y = 1.0;

        PlotPanel(List<PiecewiseLinearFunction> functions,
                  List<String> names,
                  List<Color> colors) {
            this.functions = functions;
            this.names     = names;
            this.colors    = colors;
            setBackground(Color.WHITE);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D)g0;
            int w = getWidth(), h = getHeight();

            // 计算全局 x/y 范围
            double xMin = Double.POSITIVE_INFINITY, xMax = Double.NEGATIVE_INFINITY;
            double yMin = Double.POSITIVE_INFINITY, yMax = Double.NEGATIVE_INFINITY;
            for (PiecewiseLinearFunction f : functions) {
                if (f.head == null) continue;
                xMin = Math.min(xMin, f.head.start);
                xMax = Math.max(xMax, f.tail.end);
                for (PiecewiseLinearFunction.Segment s = f.head; s != null; s = s.next) {
                    double v1 = s.slope * s.start + s.intercept;
                    double v2 = s.slope * s.end   + s.intercept;
                    yMin = Math.min(yMin, Math.min(v1, v2));
                    yMax = Math.max(yMax, Math.max(v1, v2));
                }
            }
            if (!(xMin < xMax && yMin < yMax)) return;

            // 数据→像素 缩放
            double sx = (w - 2*MARGIN) / (xMax - xMin);
            double sy = (h - 2*MARGIN) / (yMax - yMin);
            int x0 = MARGIN, y0 = h - MARGIN;

            // 画网格
            g.setStroke(new BasicStroke(1f));
            g.setColor(new Color(0xDDDDDD));
            for (double x = Math.ceil(xMin/GRID_STEP_X)*GRID_STEP_X; x <= xMax; x += GRID_STEP_X) {
                int px = x0 + (int)((x - xMin)*sx);
                g.drawLine(px, MARGIN, px, h - MARGIN);
            }
            for (double y = Math.ceil(yMin/GRID_STEP_Y)*GRID_STEP_Y; y <= yMax; y += GRID_STEP_Y) {
                int py = y0 - (int)((y - yMin)*sy);
                g.drawLine(MARGIN, py, w - MARGIN, py);
            }

            // 坐标轴 & 刻度
            g.setColor(Color.BLACK);
            g.setStroke(new BasicStroke(2f));
            g.drawLine(MARGIN, y0, w - MARGIN, y0);
            g.drawLine(x0, MARGIN, x0, h - MARGIN);
            g.setFont(new Font("SansSerif", Font.PLAIN, 12));
            for (double x = Math.ceil(xMin/GRID_STEP_X)*GRID_STEP_X; x <= xMax; x += GRID_STEP_X) {
                int px = x0 + (int)((x - xMin)*sx);
                g.drawLine(px, y0-5, px, y0+5);
                String xs = String.format("%.1f", x);
                g.drawString(xs, px - g.getFontMetrics().stringWidth(xs)/2, y0 + 20);
            }
            for (double y = Math.ceil(yMin/GRID_STEP_Y)*GRID_STEP_Y; y <= yMax; y += GRID_STEP_Y) {
                int py = y0 - (int)((y - yMin)*sy);
                g.drawLine(x0-5, py, x0+5, py);
                String ys = String.format("%.1f", y);
                g.drawString(ys, x0 - 10 - g.getFontMetrics().stringWidth(ys), py+5);
            }

            // 绘制每条函数及其段表达式
            g.setFont(new Font("SansSerif", Font.ITALIC, 11));
            for (int i = 0; i < functions.size(); i++) {
                PiecewiseLinearFunction f = functions.get(i);
                g.setColor(colors.get(i));
                g.setStroke(new BasicStroke(2f));
                for (PiecewiseLinearFunction.Segment s = f.head; s != null; s = s.next) {
                    // 画线段
                    double x1 = s.start, y1 = s.slope * x1 + s.intercept;
                    double x2 = s.end,   y2 = s.slope * x2 + s.intercept;
                    int px1 = x0 + (int)((x1 - xMin)*sx), py1 = y0 - (int)((y1 - yMin)*sy);
                    int px2 = x0 + (int)((x2 - xMin)*sx), py2 = y0 - (int)((y2 - yMin)*sy);
                    g.draw(new Line2D.Double(px1, py1, px2, py2));

                    // 标注表达式
                    double xm = (x1 + x2) / 2;
                    double ym = s.slope * xm + s.intercept;
                    int pxm = x0 + (int)((xm - xMin)*sx);
                    int pym = y0 - (int)((ym - yMin)*sy);
                    String expr = String.format("y=%.2f·t+ .2f", s.slope, s.intercept);
                    // 背景矩形以提高可读性
                    FontMetrics fm = g.getFontMetrics();
                    int tw = fm.stringWidth(expr), th = fm.getHeight();
                    g.setColor(new Color(255,255,255,200));
                    g.fillRect(pxm - tw/2 - 2, pym - th + 2, tw + 4, th);
                    g.setColor(colors.get(i).darker());
                    g.drawString(expr, pxm - tw/2, pym);
                }
            }

            // 图例
            int lx = w - MARGIN - 150, ly = MARGIN + 10;
            g.setFont(new Font("SansSerif", Font.PLAIN, 14));
            for (int i = 0; i < names.size(); i++) {
                g.setColor(colors.get(i));
                g.fillRect(lx, ly + i*25, 20, 4);
                g.setColor(Color.BLACK);
                g.drawString(names.get(i), lx + 30, ly + i*25 + 5);
            }
        }
    }
	
    public static void main(String[] args) {
        // 构造几个示例函数
        PiecewiseLinearFunction f1 = new PiecewiseLinearFunction(0, 100);
        f1.addSegment(0, 1, 1.0, 0.0);
        f1.addSegment(1, 3, -0.5, 2.0);
        f1.addSegment(3, 4, 1.0, 0.0);
        f1.addSegment(4, 5, 1.0, 0.0);
        f1.addSegment(5, 9, -0.5, 2.0);
        f1.addSegment(9, 20, 1.0, 0.0);

        PiecewiseLinearFunction f2 = new PiecewiseLinearFunction(0, 100);
        f2.addSegment(0, 2, 0.2, 1.0);
        f2.addSegment(2, 3, 0.0, 1.4);

        // 只需一行，绘图并保存在 piecwise_plot.png
        plotAndSave(List.of(f1, f2));
    }
}
