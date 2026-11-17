import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;
import javax.sound.sampled.*;
import static java.awt.event.KeyEvent.*;
/** BRICK BREAKER - ADVANCED EDITION (pure Java, single file). Run: java BrickBreaker.java */
public class BrickBreaker extends Canvas implements Runnable {
    static final int W = 960, H = 720;
    final Game game = new Game();
    double scale = 1, offX, offY;
    BrickBreaker() {
        setPreferredSize(new Dimension(W, H)); setBackground(Color.BLACK); setIgnoreRepaint(true); setFocusTraversalKeysEnabled(false);
        addKeyListener(new KeyAdapter() { public void keyPressed(KeyEvent e) { game.keyDown(e.getKeyCode()); } public void keyReleased(KeyEvent e) { game.keyUp(e.getKeyCode()); } });
        MouseAdapter m = new MouseAdapter() {
            public void mouseMoved(MouseEvent e) { game.mouseMove((e.getX() - offX) / scale); } public void mouseDragged(MouseEvent e) { mouseMoved(e); }
            public void mousePressed(MouseEvent e) { game.mouseDown = true; game.action(); } public void mouseReleased(MouseEvent e) { game.mouseDown = false; } };
        addMouseListener(m); addMouseMotionListener(m); }
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> { JFrame f = new JFrame("Brick Breaker - Advanced Edition"); BrickBreaker c = new BrickBreaker();
            f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE); f.add(c); f.pack(); f.setMinimumSize(new Dimension(640, 480)); f.setLocationRelativeTo(null); f.setVisible(true);
            c.requestFocus(); new Thread(c, "game-loop").start(); }); }
    /** Game loop: update with real elapsed time, draw, cap at 144 fps. */
    public void run() {
        createBufferStrategy(3); BufferStrategy bs = getBufferStrategy();
        long last = System.nanoTime(), frameNs = 1_000_000_000L / 144;
        while (true) { long now = System.nanoTime(); game.update(Math.min((now - last) / 1e9, 0.05)); last = now; int cw = getWidth(), ch = getHeight();
            scale = Math.min(cw / (double) W, ch / (double) H); offX = (cw - W * scale) / 2; offY = (ch - H * scale) / 2; do { do {
                Graphics2D g = (Graphics2D) bs.getDrawGraphics(); g.setColor(Color.BLACK); g.fillRect(0, 0, cw, ch);
                g.translate(offX, offY); g.scale(scale, scale); game.render(g); g.dispose(); } while (bs.contentsRestored()); bs.show(); } while (bs.contentsLost());
            Toolkit.getDefaultToolkit().sync(); long spent = System.nanoTime() - now; if (spent < frameNs) LockSupport.parkNanos(frameNs - spent); } }
    enum State { MENU, READY, PLAY, PAUSE, CLEAR, OVER }
    enum Power {
        WIDE("W", "WIDE PADDLE", new Color(80, 220, 120), 14), MULTI("M", "MULTI BALL", new Color(70, 140, 255), 0), FIRE("F", "FIREBALL", new Color(255, 120, 40), 8),
        LASER("L", "LASER", new Color(255, 70, 110), 12), CATCH("C", "CATCH", new Color(180, 100, 255), 15), SLOW("S", "SLOW MOTION", new Color(60, 220, 230), 9), LIFE("+", "EXTRA LIFE", new Color(255, 110, 170), 0);
        final String letter, label; final Color color; final double dur;
        Power(String l, String n, Color c, double d) { letter = l; label = n; color = c; dur = d; } }
    static final int NORMAL = 0, HARD = 1, TOUGH = 2, STEEL = 3, BOMB = 4, COLS = 12, BW = 64, BH = 24, GAP = 4, X0 = (W - (COLS * (BW + GAP) - GAP)) / 2, Y0 = 104;
    static final int[] HP = { 1, 2, 3, 999, 1 };
    static final Color[] PAL = { new Color(255, 77, 109), new Color(255, 158, 77), new Color(255, 216, 77), new Color(77, 255, 136), new Color(77, 210, 255), new Color(176, 77, 255) };
    static final Color[] TYPE_COL = { null, new Color(110, 150, 190), new Color(70, 80, 100), new Color(150, 160, 170), new Color(255, 90, 40) };
    static final double TOP = 58, R = 8, PY = H - 62, PH = 14;
    /** '.' empty, '1'-'6' coloured, 'h' hard (2 hits), 't' tough (3 hits), 's' steel, 'x' explosive. */
    static final String[][] LEVELS = {
        { "111111111111", "222222222222", "333333333333", "444444444444", "555555555555" }, { "hhhhhhhhhhhh", "222222222222", "333333333333", "444444444444", "555555555555" },
        { ".....11.....", "....2222....", "...333333...", "..44444444..", ".5555555555.", "666666666666" }, { "1.2.3.4.5.6.", ".1.2.3.4.5.6", "1.2.3.4.5.6.", ".h.h.h.h.h.h", "s..........s" },
        { "hhhhhhhhhhhh", "1x1x1x1x1x1x", "222222222222", "3x3x3x3x3x3x", "444444444444" }, { ".....hh.....", "....h22h....", "...h2332h...", "..h234432h..", "...h2332h...", "....h22h....", ".....hh....." },
        { "t1111tt1111t", "t2222tt2222t", "s3333ss3333s", "t4444tt4444t", "t5555tt5555t" }, { "ssssssssssss", "tttttttttttt", "hxhxhxhxhxhx", "222222222222", "x1x1x1x1x1x1", "tttttttttttt" } };
    static class Ball { double x, y, vx, vy, off; boolean stuck = true; final ArrayList<double[]> trail = new ArrayList<>(); }
    static class Brick { double x, y, flash, pop, delay; int type, hp, maxHp; Color col; boolean alive = true; double cx() { return x + BW / 2.0; } double cy() { return y + BH / 2.0; } }
    /** Short-lived things: capsules, laser bolts, particles (kind 0 spark, 1 shard, 2 ring), score popups. */
    static class Obj { int kind; double x, y, vx, vy, life, max, size, rot, vr, t; Color c; String s; boolean big; Power p; }
    static class Game {
        final Random rnd = new Random(); final Path file = Paths.get("brickbreaker_highscore.txt"); final Sfx sfx = new Sfx(); State state = State.MENU;
        double px = W / 2.0, pvx, targetX = W / 2.0, pw = 110, squash;
        boolean left, right, useMouse, mouseDown, spaceDown, newHigh;
        final List<Ball> balls = new ArrayList<>(); List<Brick> bricks = new ArrayList<>();
        final List<Obj> caps = new ArrayList<>(), bolts = new ArrayList<>(), parts = new ArrayList<>(), pops = new ArrayList<>();
        final double[][] stars = new double[110][5];
        final double[] tm = new double[Power.values().length];
        int score, lives = 3, level = 1, combo, bestCombo, broken, hits, hiScore;
        double shownScore, baseSpeed = 430, time, stateTime, levelT, shake, lifeFlash, laserCd;
        BufferedImage bg; float bgHue = -1; static final BufferedImage VIGNETTE = makeVignette();
        Game() {
            for (double[] s : stars) { s[0] = rnd.nextDouble() * W; s[1] = rnd.nextDouble() * H; s[2] = 8 + rnd.nextDouble() * 40; s[3] = 0.6 + rnd.nextDouble() * 1.6; s[4] = rnd.nextDouble() * 6.28; }
            try { hiScore = Integer.parseInt(Files.readString(file).trim()); } catch (Exception e) { } loadLevel(1); resetBall(); }
        static double clamp(double v, double a, double b) { return Math.max(a, Math.min(b, v)); }
        static Color alpha(Color c, double a) { return new Color(c.getRed(), c.getGreen(), c.getBlue(), (int) Math.round(clamp(a, 0, 1) * 255)); }
        static Color mix(Color a, Color b, double t) { t = clamp(t, 0, 1); return new Color((int) (a.getRed() + (b.getRed() - a.getRed()) * t), (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t), (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t)); }
        static double easeOutBack(double t) { double c1 = 1.70158, c3 = c1 + 1; return 1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2); }
        boolean on(Power p) { return tm[p.ordinal()] > 0; }
        void snd(String k, int v) { if (state != State.MENU) sfx.play(k, v); }
        void setState(State s) { state = s; stateTime = 0; }
        void newGame(int start) { score = 0; shownScore = 0; lives = 3; level = start; combo = bestCombo = broken = 0; newHigh = false; clearEffects(); loadLevel(start); resetBall(); setState(State.READY); }
        void clearEffects() { Arrays.fill(tm, 0); caps.clear(); bolts.clear(); }
        void loadLevel(int n) { levelT = 0; hits = 0; baseSpeed = Math.min(430 + 30 * (n - 1), 740); bricks = new ArrayList<>();
            String[] map = n <= LEVELS.length ? LEVELS[n - 1] : generate(n); for (int r = 0; r < map.length; r++) for (int c = 0; c < COLS; c++) {
                char ch = map[r].charAt(c); if (ch == '.') continue;
                Brick b = new Brick(); b.x = X0 + c * (BW + GAP); b.y = Y0 + r * (BH + GAP); b.delay = r * 0.07 + c * 0.025;
                b.type = ch <= '6' ? NORMAL : "htsx".indexOf(ch) + 1; b.hp = b.maxHp = HP[b.type]; b.col = b.type == NORMAL ? PAL[ch - '1'] : TYPE_COL[b.type]; bricks.add(b);
            } float hue = (float) (((n - 1) * 0.11 + 0.62) % 1.0); if (hue != bgHue) { bg = makeBg(hue); bgHue = hue; } }
        /** Endless levels: random but mirrored patterns that get harder with the level number. */
        String[] generate(int n) { Random r = new Random(n * 977L); String[] m = new String[6 + r.nextInt(3)]; for (int i = 0; i < m.length; i++) {
                char[] row = new char[COLS]; for (int c = 0; c < COLS / 2; c++) { double p = r.nextDouble(); char ch;
                    if (p < 0.10) ch = '.'; else if (p < 0.56) ch = (char) ('1' + r.nextInt(6)); else if (p < 0.66 + Math.min(0.12, (n - 9) * 0.02)) ch = 'h';
                    else if (p < 0.82) ch = 't'; else if (p < 0.94) ch = 'x'; else ch = c == 0 ? 's' : '2'; if (i == 0 && ch == '.') ch = '1'; row[c] = row[COLS - 1 - c] = ch;
                } m[i] = new String(row); } return m; }
        void resetBall() { balls.clear(); Ball b = new Ball(); b.x = px; b.y = PY - PH / 2 - R - 1; balls.add(b); combo = hits = 0; }
        double currentSpeed() { return baseSpeed * (1 + Math.min(0.30, hits * 0.012)) * (on(Power.SLOW) ? 0.68 : 1.0); }
        void launch(Ball b) { double a = clamp(b.off / (pw / 2), -1, 1) * 0.9 + (rnd.nextDouble() - 0.5) * 0.25, sp = currentSpeed(); b.stuck = false; b.vx = sp * Math.sin(a); b.vy = -sp * Math.cos(a); snd("launch", 0); }
        void loseLife() { if (state == State.MENU) { resetBall(); return; } lives--; combo = 0; shake = 14; lifeFlash = 1; clearEffects(); snd("lose", 0);
            if (lives <= 0) { setState(State.OVER); if (score > hiScore) { hiScore = score; newHigh = true; try { Files.writeString(file, "" + hiScore); } catch (Exception e) { } } }
            else { setState(State.READY); resetBall(); } }
        void levelClear() { setState(State.CLEAR); caps.clear(); bolts.clear(); int bonus = 100 * level + 50 * lives; score += bonus;
            popup("LEVEL BONUS +" + bonus, W / 2.0, 352, new Color(255, 230, 120), true); snd("clear", 0);
            for (int i = 0; i < 90; i++) spark(rnd.nextDouble() * W, 120 + rnd.nextDouble() * 200, PAL[rnd.nextInt(6)], 1, 380); }
        void nextLevel() { level++; loadLevel(level); resetBall(); clearEffects(); setState(State.READY); }
        void keyDown(int k) { if (k == VK_LEFT || k == VK_A) { left = true; useMouse = false; } else if (k == VK_RIGHT || k == VK_D) { right = true; useMouse = false; }
            else if (k == VK_SPACE || k == VK_ENTER) { spaceDown = true; action(); }
            else if (k == VK_P || k == VK_ESCAPE) { if (state == State.PLAY) setState(State.PAUSE); else if (state == State.PAUSE) setState(State.PLAY); }
            else if (k == VK_M) sfx.enabled = !sfx.enabled; else if (k == VK_R && state != State.MENU) newGame(1); }
        void keyUp(int k) { if (k == VK_LEFT || k == VK_A) left = false; else if (k == VK_RIGHT || k == VK_D) right = false; else if (k == VK_SPACE || k == VK_ENTER) spaceDown = false; }
        void mouseMove(double x) { targetX = x; useMouse = true; }
        /** Click or SPACE: does the right thing for the current state. */
        void action() { if (state == State.MENU) newGame(1); else if (state == State.READY || state == State.PLAY) {
                boolean released = false; for (Ball b : balls) if (b.stuck) { launch(b); released = true; }
                if (state == State.READY) setState(State.PLAY); else if (!released) shoot();
            } else if (stateTime > 0.9) { if (state == State.CLEAR) nextLevel(); else if (state == State.OVER) newGame(level); } }
        void shoot() { if (!on(Power.LASER) || laserCd > 0) return; laserCd = 0.2;
            for (int s = -1; s <= 1; s += 2) { Obj b = new Obj(); b.x = px + s * (pw / 2 - 12); b.y = PY - 10; bolts.add(b); } snd("laser", 0); }
        void update(double dt) { time += dt; for (double[] s : stars) { s[1] += s[2] * dt; if (s[1] > H) { s[1] = 0; s[0] = rnd.nextDouble() * W; } }
            if (state == State.PAUSE) return;
            stateTime += dt; levelT += dt; shake *= Math.exp(-8 * dt); if (shake < 0.05) shake = 0; lifeFlash = Math.max(0, lifeFlash - dt * 2);
            shownScore += (score - shownScore) * (1 - Math.exp(-9 * dt)); if (Math.abs(score - shownScore) < 0.5) shownScore = score; updatePaddle(dt);
            for (Brick k : bricks) { k.flash = Math.max(0, k.flash - dt * 5); k.pop = Math.max(0, k.pop - dt * 6); } parts.removeIf(p -> {
                if ((p.life -= dt) <= 0) return true; p.x += p.vx * dt; p.y += p.vy * dt;
                if (p.kind == 1) { p.vy += 900 * dt; p.rot += p.vr * dt; } else if (p.kind == 0) { double d = Math.exp(-3 * dt); p.vx *= d; p.vy *= d; } return false; });
            pops.removeIf(p -> { p.life -= dt; p.y -= 38 * dt; return p.life <= 0; }); for (Ball b : balls) if (b.stuck) { b.x = px + b.off; b.y = PY - PH / 2 - R - 1; }
            if (state == State.PLAY || state == State.MENU) updatePlay(dt);
            for (Ball b : balls) { b.trail.add(new double[]{ b.x, b.y }); while (b.trail.size() > 16) b.trail.remove(0); } }
        void updatePaddle(double dt) { double old = px;
            if (state == State.MENU) { targetX = balls.isEmpty() ? W / 2.0 : balls.get(0).x + Math.sin(time * 1.7) * 26; useMouse = true; }
            if (useMouse) { px += (targetX - px) * (1 - Math.exp(-26 * dt)); pvx = (px - old) / Math.max(dt, 1e-4); }
            else { double a = (right ? 1 : 0) - (left ? 1 : 0); if (a != 0) pvx += a * 7000 * dt; else pvx *= Math.exp(-16 * dt); pvx = clamp(pvx, -980, 980); px += pvx * dt; }
            pw += ((on(Power.WIDE) ? 172 : 110) - pw) * (1 - Math.exp(-12 * dt));
            double lo = pw / 2, hi = W - pw / 2; if (px < lo) { px = lo; pvx = 0; } else if (px > hi) { px = hi; pvx = 0; } squash *= Math.exp(-10 * dt); }
        void updatePlay(double dt) { for (int i = 0; i < tm.length; i++) tm[i] = Math.max(0, tm[i] - dt);
            laserCd -= dt; if (state == State.PLAY && (spaceDown || mouseDown)) shoot();
            if (state == State.MENU) for (Ball b : balls) if (b.stuck && stateTime % 100 > 0.9 && time > 0.9) launch(b); double target = currentSpeed();
            for (Ball b : balls) if (!b.stuck) { double sp = Math.hypot(b.vx, b.vy); if (sp > 1e-6) { double f = 1 + (target / sp - 1) * Math.min(1, 6 * dt); b.vx *= f; b.vy *= f; } fixDir(b); }
            int n = (int) Math.ceil(Math.max(dt, 1e-4) / (1 / 240.0)); for (int i = 0; i < n; i++) for (int j = balls.size() - 1; j >= 0; j--) moveBall(balls.get(j), dt / n);
            if (on(Power.FIRE)) for (Ball b : balls) if (!b.stuck) spark(b.x, b.y, new Color(255, 150 + rnd.nextInt(80), 40), 1, 90);
            balls.removeIf(b -> b.y - R > H + 10); if (balls.isEmpty()) loseLife(); bolts.removeIf(b -> { b.y -= 950 * dt; boolean dead = b.y < TOP;
                for (Brick k : bricks) if (!dead && k.alive && b.x > k.x && b.x < k.x + BW && b.y > k.y && b.y < k.y + BH + 8) { hitBrick(k, 1); dead = true; spark(b.x, b.y, new Color(255, 90, 120), 6, 220); }
                return dead; }); caps.removeIf(c -> { c.y += 175 * dt; c.t += dt;
                if (c.y > PY - PH / 2 - 8 && c.y < PY + PH && Math.abs(c.x - px) < pw / 2 + 22) { applyPower(c.p, c.x, c.y); return true; } return c.y > H + 30; });
            if (bricks.stream().noneMatch(k -> k.alive && k.type != STEEL)) {
                if (state == State.MENU) { level = 1 + rnd.nextInt(LEVELS.length); loadLevel(level); resetBall(); } else levelClear(); } }
        void fixDir(Ball b) { double sp = Math.hypot(b.vx, b.vy); if (sp < 1e-6) { b.vy = -currentSpeed(); return; } double minV = 0.27 * sp;
            if (Math.abs(b.vy) < minV) { b.vy = Math.copySign(minV, b.vy == 0 ? -1 : b.vy); b.vx = Math.copySign(Math.sqrt(Math.max(0, sp * sp - b.vy * b.vy)), b.vx); } }
        void moveBall(Ball b, double h) { if (b.stuck) return; double prevY = b.y; b.x += b.vx * h; b.y += b.vy * h;
            if (b.x < R) { b.x = R; b.vx = Math.abs(b.vx); snd("wall", 0); fixDir(b); } else if (b.x > W - R) { b.x = W - R; b.vx = -Math.abs(b.vx); snd("wall", 0); fixDir(b); }
            if (b.y < TOP + R) { b.y = TOP + R; b.vy = Math.abs(b.vy); snd("wall", 0); } double top = PY - PH / 2;
            if (b.vy > 0 && prevY + R <= top + 7 && b.y + R >= top && b.y - R <= PY + PH / 2 && b.x >= px - pw / 2 - R * 0.6 && b.x <= px + pw / 2 + R * 0.6) {
                hits++; double q = clamp((b.x - px) / (pw / 2), -1, 1), ang = q * Math.toRadians(62), sp = currentSpeed();
                b.vx = sp * Math.sin(ang) + pvx * 0.07; b.vy = -sp * Math.cos(ang);
                if (Math.abs(b.vx) < 0.12 * sp) b.vx = (q != 0 ? Math.signum(q) : rnd.nextBoolean() ? 1 : -1) * 0.12 * sp;
                fixDir(b); b.y = top - R; combo = 0; squash = 1; snd("paddle", 0); spark(b.x, top, new Color(120, 230, 255), 8, 220);
                if (on(Power.CATCH)) { b.stuck = true; b.off = b.x - px; } return; } Brick best = null; double bestD = 1e18, nx = 0, ny = -1; for (Brick k : bricks) {
                if (!k.alive || levelT < k.delay + 0.55) continue; double dx = b.x - clamp(b.x, k.x, k.x + BW), dy = b.y - clamp(b.y, k.y, k.y + BH), d2 = dx * dx + dy * dy;
                if (d2 >= R * R) continue; if (on(Power.FIRE) && k.type != STEEL) { hitBrick(k, 99); continue; } if (d2 < bestD) { bestD = d2; best = k;
                    if (d2 > 1e-9) { double d = Math.sqrt(d2); nx = dx / d; ny = dy / d; }
                    else { double l = b.x - k.x, r = k.x + BW - b.x, t = b.y - k.y, bt = k.y + BH - b.y, m = Math.min(Math.min(l, r), Math.min(t, bt)); nx = ny = 0; if (m == l) nx = -1; else if (m == r) nx = 1; else if (m == t) ny = -1; else ny = 1; }
                } } if (best != null) { double dot = b.vx * nx + b.vy * ny; if (dot < 0) { b.vx -= 2 * dot * nx; b.vy -= 2 * dot * ny; }
                double push = R - Math.sqrt(bestD) + 0.05; b.x += nx * push; b.y += ny * push; fixDir(b); hitBrick(best, 1); } }
        void hitBrick(Brick k, int dmg) { if (!k.alive) return; k.flash = 1;
            if (k.type == STEEL) { shake = Math.max(shake, 1.5); snd("clang", 0); spark(k.cx(), k.cy(), Color.WHITE, 4, 160); return; }
            k.pop = 1; if ((k.hp -= dmg) > 0) { snd("hit", k.hp); spark(k.cx(), k.cy(), mix(k.col, Color.WHITE, 0.5), 5, 170); return; }
            k.alive = false; bestCombo = Math.max(bestCombo, ++combo); broken++; int mult = Math.min(8, 1 + combo / 4), pts = (k.type == BOMB ? 25 : 10 * k.maxHp) * mult;
            if (state != State.MENU) { score += pts; popup("+" + pts, k.cx(), k.cy(), mult > 1 ? new Color(255, 230, 110) : Color.WHITE, mult > 2); }
            for (int i = 0; i < 11; i++) shard(k); spark(k.cx(), k.cy(), mix(k.col, Color.WHITE, 0.4), 10, 260); shake = Math.max(shake, k.type == BOMB ? 11 : 2.2);
            snd(k.type == BOMB ? "bomb" : "brick", Math.min(combo, 12)); if (rnd.nextDouble() < (k.type == BOMB ? 0.30 : 0.13)) dropCapsule(k.cx(), k.cy());
            if (k.type == BOMB) { ring(k.cx(), k.cy(), new Color(255, 160, 60), 120);
                for (Brick o : new ArrayList<>(bricks)) if (o.alive && o != k && Math.abs(o.cx() - k.cx()) < 75 && Math.abs(o.cy() - k.cy()) < 34) hitBrick(o, 2); } }
        void dropCapsule(double x, double y) { int[] w = { 20, 18, 12, 12, 14, 10, lives < 5 ? 6 : 0 };
            int r = rnd.nextInt(Arrays.stream(w).sum()), i = 0; while (r >= w[i]) r -= w[i++]; Obj c = new Obj(); c.x = x; c.y = y; c.p = Power.values()[i]; caps.add(c); }
        void applyPower(Power p, double x, double y) { score += 50; popup(p.label, x, y - 24, p.color, true); ring(x, y, p.color, 90); snd("power", 0);
            if (p.dur > 0) tm[p.ordinal()] = p.dur; else if (p == Power.LIFE) lives = Math.min(5, lives + 1); else {
                Ball src = balls.stream().filter(b -> !b.stuck).findFirst().orElse(null); if (src != null) for (double a : new double[]{ -0.5, 0.5 }) if (balls.size() < 6) {
                    Ball n = new Ball(); n.stuck = false; n.x = src.x; n.y = src.y;
                    n.vx = src.vx * Math.cos(a) - src.vy * Math.sin(a); n.vy = src.vx * Math.sin(a) + src.vy * Math.cos(a); balls.add(n); } } }
        Obj fx(int kind, double x, double y, Color c, double life, double size) { Obj p = new Obj(); p.kind = kind; p.x = x; p.y = y; p.c = c; p.max = p.life = life; p.size = size; return p; }
        void add(Obj p) { if (parts.size() < 1400) parts.add(p); }
        void spark(double x, double y, Color c, int n, double speed) { for (int i = 0; i < n; i++) {
                double a = rnd.nextDouble() * 6.283, s = speed * (0.3 + rnd.nextDouble() * 0.7); Obj p = fx(0, x, y, c, 0.3 + rnd.nextDouble() * 0.35, 1.6 + rnd.nextDouble() * 1.6);
                p.vx = Math.cos(a) * s; p.vy = Math.sin(a) * s; add(p); } }
        void shard(Brick k) { Obj p = fx(1, k.x + rnd.nextDouble() * BW, k.y + rnd.nextDouble() * BH, k.col, 0.7 + rnd.nextDouble() * 0.5, 4 + rnd.nextDouble() * 6);
            p.vx = (p.x - k.cx()) * 5 + (rnd.nextDouble() - 0.5) * 120; p.vy = -90 - rnd.nextDouble() * 200; p.rot = rnd.nextDouble() * 6; p.vr = (rnd.nextDouble() - 0.5) * 16; add(p);
        }
        void ring(double x, double y, Color c, double size) { add(fx(2, x, y, c, 0.5, size)); }
        void popup(String s, double x, double y, Color c, boolean big) { Obj p = fx(0, x, y, c, 1.1, 0); p.s = s; p.big = big; pops.add(p); }
        static GradientPaint grad(double y1, Color a, double y2, Color b) { return new GradientPaint(0, (float) y1, a, 0, (float) y2, b); }
        static RadialGradientPaint radial(double x, double y, double r, Color a, Color b) { return new RadialGradientPaint(new Point2D.Double(x, y), (float) r, new float[]{ 0f, 1f }, new Color[]{ a, b }); }
        static RoundRectangle2D rrect(double x, double y, double w, double h, double arc) { return new RoundRectangle2D.Double(x, y, w, h, arc, arc); }
        static Ellipse2D oval(double cx, double cy, double r) { return new Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2); }
        static void fill(Graphics2D g, Color c, Shape s) { g.setColor(c); g.fill(s); }
        static BufferedImage makeBg(float hue) { BufferedImage im = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB); Graphics2D g = im.createGraphics();
            g.setPaint(grad(0, Color.getHSBColor(hue, 0.75f, 0.17f), H, Color.getHSBColor((hue + 0.08f) % 1f, 0.85f, 0.06f))); g.fillRect(0, 0, W, H);
            g.setPaint(radial(W / 2.0, H * 0.35, 520, alpha(Color.getHSBColor(hue, 0.6f, 0.9f), 0.16), alpha(Color.BLACK, 0))); g.fillRect(0, 0, W, H); g.dispose(); return im;
        }
        static BufferedImage makeVignette() { BufferedImage im = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB); Graphics2D v = im.createGraphics();
            v.setPaint(new RadialGradientPaint(new Point2D.Double(W / 2.0, H / 2.0), W * 0.72f, new float[]{ 0.55f, 1f }, new Color[]{ new Color(0, 0, 0, 0), new Color(0, 0, 0, 150) })); v.fillRect(0, 0, W, H); v.dispose(); return im;
        }
        static final Font F_BIG = new Font("SansSerif", Font.BOLD, 64), F_MID = new Font("SansSerif", Font.BOLD, 30), F_UI = new Font("SansSerif", Font.BOLD, 22), F_SM = new Font("SansSerif", Font.PLAIN, 15), F_TINY = new Font("SansSerif", Font.BOLD, 12);
        void render(Graphics2D g) {
            g.addRenderingHints(Map.of(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON, RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE, RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON, RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY));
            g.drawImage(bg, 0, 0, null);
            for (double[] s : stars) fill(g, new Color(255, 255, 255, (int) (40 + 130 * (0.5 + 0.5 * Math.sin(time * 2 + s[4])) * (s[3] / 2.2))), new Ellipse2D.Double(s[0], s[1], s[3], s[3]));
            AffineTransform old = g.getTransform(); if (shake > 0.05) g.translate((rnd.nextDouble() - 0.5) * shake, (rnd.nextDouble() - 0.5) * shake);
            for (Brick k : bricks) drawBrick(g, k);
            for (Obj b : bolts) { fill(g, new Color(255, 80, 110, 90), rrect(b.x - 5, b.y - 2, 10, 26, 8)); fill(g, new Color(255, 230, 235), rrect(b.x - 1.5, b.y, 3, 20, 3)); }
            for (Obj c : caps) drawCapsule(g, c.p, c.x, c.y, 1 + 0.06 * Math.sin(c.t * 9), true);
            drawPaddle(g); for (Ball b : balls) drawBall(g, b); for (Obj p : parts) drawParticle(g, p);
            for (Obj p : pops) text(g, p.s, p.x, p.y, p.big ? F_UI : F_SM, alpha(p.c, p.life / 0.4)); g.setTransform(old); drawHud(g); g.drawImage(VIGNETTE, 0, 0, null);
            if (lifeFlash > 0) { g.setColor(new Color(255, 40, 60, (int) (110 * lifeFlash))); g.fillRect(0, 0, W, H); } drawOverlay(g); }
        void drawBrick(Graphics2D g, Brick k) { double t = clamp((levelT - k.delay) / 0.6, 0, 1); if (!k.alive || t <= 0) return;
            double sc = 1 + 0.16 * k.pop, cx = k.cx(), cy = k.cy() - (1 - easeOutBack(t)) * 320, w = BW * sc, h = BH * sc;
            RoundRectangle2D rr = rrect(cx - w / 2, cy - h / 2, w, h, 9); Color base = mix(k.col, Color.WHITE, k.flash * 0.8); Composite oc = g.getComposite();
            if (t < 1) g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) t));
            g.setPaint(grad(cy - h / 2, mix(base, Color.WHITE, 0.28), cy + h / 2, mix(base, Color.BLACK, 0.32))); g.fill(rr);
            fill(g, new Color(255, 255, 255, 80), rrect(cx - w / 2 + 3, cy - h / 2 + 2, w - 6, 4, 4));
            g.setStroke(new BasicStroke(1.5f)); g.setColor(alpha(mix(base, Color.BLACK, 0.55), 0.95)); g.draw(rr); if (k.type == HARD || k.type == TOUGH) {
                g.setColor(k.type == TOUGH ? new Color(235, 195, 80) : new Color(215, 230, 245)); g.draw(rr);
                g.setColor(new Color(20, 25, 35, 140)); g.fill(oval(cx - w / 2 + 8, cy, 2)); g.fill(oval(cx + w / 2 - 8, cy, 2));
                g.setColor(new Color(10, 12, 18, 220)); g.setStroke(new BasicStroke(1.6f)); double[][] cr = { { -10, -3, -9, 0 }, { 12, 5, 14, -2 } };
                for (int i = 0; i < Math.min(2, k.maxHp - k.hp); i++) { double[] c = cr[i]; g.draw(new Line2D.Double(cx + c[0], cy - h / 2, cx + c[1], cy + c[3])); g.draw(new Line2D.Double(cx + c[1], cy + c[3], cx + c[2], cy + h / 2)); }
            } else if (k.type == STEEL) { Shape oldClip = g.getClip(); g.clip(rr); g.setColor(new Color(255, 255, 255, 55)); g.setStroke(new BasicStroke(2f));
                for (int i = -4; i < 12; i++) g.draw(new Line2D.Double(cx - w / 2 + i * 9, cy + h / 2, cx - w / 2 + i * 9 + 18, cy - h / 2)); g.setClip(oldClip);
            } else if (k.type == BOMB) {
                fill(g, new Color(255, 240, 120, (int) (140 + 100 * (0.5 + 0.5 * Math.sin(time * 7)))), oval(cx, cy, 6)); g.setColor(new Color(120, 20, 0)); g.setStroke(new BasicStroke(2f)); g.draw(oval(cx, cy, 6));
            } g.setComposite(oc); }
        void drawCapsule(Graphics2D g, Power p, double x, double y, double s, boolean glow) { double w = 42 * s, h = 22 * s;
            if (glow) for (int i = 3; i >= 1; i--) fill(g, alpha(p.color, 0.10), rrect(x - w / 2 - i * 3, y - h / 2 - i * 3, w + i * 6, h + i * 6, h + i * 6));
            RoundRectangle2D rr = rrect(x - w / 2, y - h / 2, w, h, h); g.setPaint(grad(y - h / 2, mix(p.color, Color.WHITE, 0.45), y + h / 2, mix(p.color, Color.BLACK, 0.3))); g.fill(rr);
            g.setColor(new Color(255, 255, 255, 190)); g.setStroke(new BasicStroke(1.5f)); g.draw(rr);
            g.setFont(F_UI.deriveFont(Font.BOLD, (float) (15 * s))); FontMetrics fm = g.getFontMetrics();
            g.setColor(Color.WHITE); g.drawString(p.letter, (float) (x - fm.stringWidth(p.letter) / 2.0), (float) (y + fm.getAscent() / 2.0 - 2)); }
        void drawPaddle(Graphics2D g) { double w = pw * (1 + 0.10 * squash), h = PH * (1 - 0.25 * squash), cy = PY + (PH - h) / 2;
            Color c = on(Power.FIRE) ? new Color(255, 140, 50) : on(Power.LASER) ? new Color(255, 90, 120) : new Color(70, 210, 255);
            for (int i = 4; i >= 1; i--) fill(g, alpha(c, 0.07), rrect(px - w / 2 - i * 3, cy - h / 2 - i * 3, w + i * 6, h + i * 6, 16 + i * 6));
            g.setPaint(grad(cy - h / 2, mix(c, Color.WHITE, 0.55), cy + h / 2, mix(c, Color.BLACK, 0.35))); g.fill(rrect(px - w / 2, cy - h / 2, w, h, 14));
            fill(g, new Color(255, 255, 255, 120), rrect(px - w / 2 + 6, cy - h / 2 + 1.5, w - 12, 3, 3));
            if (on(Power.LASER)) { Color lc = new Color(255, 220, 225); fill(g, lc, rrect(px - w / 2 + 8, cy - h / 2 - 8, 8, 10, 3)); fill(g, lc, rrect(px + w / 2 - 16, cy - h / 2 - 8, 8, 10, 3)); }
            if (on(Power.CATCH)) fill(g, new Color(190, 120, 255, 200), rrect(px - w / 2 + 4, cy - h / 2 - 3, w - 8, 3, 3)); }
        void drawBall(Graphics2D g, Ball b) { Color c = on(Power.FIRE) ? new Color(255, 150, 50) : Color.WHITE; int n = b.trail.size();
            for (int i = 0; i < n; i++) { double f = (i + 1.0) / n; double[] p = b.trail.get(i); fill(g, alpha(c, 0.30 * f * f), oval(p[0], p[1], R * (0.35 + 0.65 * f))); }
            g.setPaint(radial(b.x, b.y, R * 3.4, alpha(c, 0.45), alpha(c, 0))); g.fill(oval(b.x, b.y, R * 3.4));
            g.setPaint(radial(b.x - 2.5, b.y - 2.5, R * 1.5, Color.WHITE, mix(c, new Color(150, 190, 255), 0.6))); g.fill(oval(b.x, b.y, R)); }
        void drawParticle(Graphics2D g, Obj p) { double a = clamp(p.life / p.max, 0, 1);
            if (p.kind == 0) { g.setColor(alpha(p.c, a)); g.setStroke(new BasicStroke((float) (p.size * a), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)); g.draw(new Line2D.Double(p.x, p.y, p.x - p.vx * 0.035, p.y - p.vy * 0.035)); }
            else if (p.kind == 1) { AffineTransform t = g.getTransform(); g.translate(p.x, p.y); g.rotate(p.rot); fill(g, alpha(p.c, a), new Rectangle2D.Double(-p.size / 2, -p.size * 0.3, p.size, p.size * 0.6)); g.setTransform(t); }
            else { g.setColor(alpha(p.c, a * 0.9)); g.setStroke(new BasicStroke(3f)); g.draw(oval(p.x, p.y, (1 - a) * p.size)); } }
        static void text(Graphics2D g, String s, double cx, double y, Font f, Color c) { g.setFont(f); double x = cx - g.getFontMetrics().stringWidth(s) / 2.0;
            g.setColor(new Color(0, 0, 0, (int) (150 * c.getAlpha() / 255.0))); g.drawString(s, (float) (x + 2), (float) (y + 2)); g.setColor(c); g.drawString(s, (float) x, (float) y);
        }
        static void glowText(Graphics2D g, String s, double cx, double y, Font f, Color c) {
            g.setFont(f); double x = cx - g.getFontMetrics().stringWidth(s) / 2.0; g.setColor(alpha(c, 0.07));
            for (int i = 3; i >= 1; i--) for (int a = 0; a < 8; a++) g.drawString(s, (float) (x + Math.cos(a * Math.PI / 4) * i * 2.2), (float) (y + Math.sin(a * Math.PI / 4) * i * 2.2));
            text(g, s, cx, y, f, c); }
        static Path2D heart(double cx, double cy, double s) { Path2D.Double p = new Path2D.Double(); p.moveTo(cx, cy + s * 0.9);
            p.curveTo(cx - s * 1.7, cy - s * 0.1, cx - s * 0.8, cy - s * 1.1, cx, cy - s * 0.35); p.curveTo(cx + s * 0.8, cy - s * 1.1, cx + s * 1.7, cy - s * 0.1, cx, cy + s * 0.9); p.closePath(); return p;
        }
        static void stat(Graphics2D g, String label, String value, int x, Color c) { g.setFont(F_TINY); g.setColor(new Color(170, 185, 210)); g.drawString(label, x, 20); g.setFont(F_UI); g.setColor(c); g.drawString(value, x, 45); }
        void drawHud(Graphics2D g) {
            g.setPaint(grad(0, new Color(0, 0, 0, 150), 56, new Color(0, 0, 0, 70))); g.fillRect(0, 0, W, 56); g.setColor(new Color(255, 255, 255, 40)); g.fillRect(0, 56, W, 1);
            stat(g, "SCORE", String.format("%,d", Math.round(shownScore)), 24, Color.WHITE); stat(g, "BEST", String.format("%,d", Math.max(hiScore, score)), 190, new Color(255, 215, 110));
            text(g, "LEVEL " + level, W / 2.0, 36, F_UI, Color.WHITE); for (int i = 0; i < Math.max(lives, 0); i++) {
                Path2D hp = heart(W - 36 - i * 32, 30, 9); g.setColor(new Color(255, 80, 120, 70)); g.setStroke(new BasicStroke(5f)); g.draw(hp);
                g.setPaint(grad(20, new Color(255, 130, 160), 40, new Color(230, 40, 90))); g.fill(hp); } if (state == State.PLAY || state == State.PAUSE) {
                if (combo >= 2) text(g, "COMBO x" + combo + "   (score x" + Math.min(8, 1 + combo / 4) + ")", W / 2.0, 90, F_UI.deriveFont((float) (24 * (1 + 0.08 * Math.sin(time * 10)))), new Color(255, 230, 110));
                int row = 0; for (Power p : Power.values()) if (p.dur > 0 && on(p)) { double y = H - 28 - row++ * 24; drawCapsule(g, p, 52, y, 0.7, false);
                    fill(g, new Color(255, 255, 255, 40), rrect(80, y - 4, 90, 8, 8)); fill(g, p.color, rrect(80, y - 4, 90 * tm[p.ordinal()] / p.dur, 8, 8)); } }
            if (!sfx.enabled) { g.setFont(F_TINY); g.setColor(new Color(255, 255, 255, 140)); g.drawString("SOUND OFF (M)", W - 120, H - 12); } }
        void drawOverlay(Graphics2D g) {
            double fade = clamp(stateTime / 0.35, 0, 1), pulse = 0.5 + 0.5 * Math.sin(time * 4), cx = W / 2.0, blink = 0.6 + 0.4 * Math.sin(time * 5);
            Color soft = new Color(200, 210, 230), gold = new Color(255, 215, 110), pale = new Color(190, 205, 235); switch (state) { case MENU: {
                    g.setColor(new Color(0, 0, 0, 120)); g.fillRect(0, 150, W, 380);
                    glowText(g, "BRICK BREAKER", cx, 270 + Math.sin(time * 2) * 5, F_BIG, Color.getHSBColor((float) ((time * 0.08) % 1), 0.55f, 1f)); text(g, "A D V A N C E D   E D I T I O N", cx, 312, F_UI, pale);
                    int i = 0; for (Power p : Power.values()) { double x = cx - 315 + i++ * 105; drawCapsule(g, p, x, 370, 0.85, false); text(g, p.label, x, 402, F_TINY, soft); }
                    text(g, "MOUSE or  < >  keys: move     CLICK / SPACE: launch & shoot     P: pause     M: sound     R: restart", cx, 450, F_SM, soft);
                    text(g, "CLICK or press SPACE to start", cx, 498, F_MID, alpha(Color.WHITE, 0.45 + 0.55 * pulse));
                    if (hiScore > 0) text(g, "BEST SCORE  " + String.format("%,d", hiScore), cx, 135, F_UI, gold); break; }
                case READY: text(g, level == 1 && lives == 3 ? "GET READY" : "LEVEL " + level, cx, 470, F_MID, alpha(Color.WHITE, fade)); text(g, "CLICK or SPACE to launch", cx, 505, F_SM, alpha(new Color(255, 230, 140), 0.4 + 0.6 * pulse)); break;
                case PAUSE: g.setColor(new Color(0, 0, 0, 150)); g.fillRect(0, 0, W, H); glowText(g, "PAUSED", cx, H / 2.0, F_BIG, Color.WHITE); text(g, "press P to resume", cx, H / 2.0 + 44, F_UI, soft); break;
                case CLEAR:
                    g.setColor(new Color(0, 0, 0, (int) (110 * fade))); g.fillRect(0, 200, W, 230); glowText(g, "LEVEL " + level + " CLEARED!", cx, 285, F_BIG.deriveFont(52f), new Color(120, 255, 170));
                    if (stateTime > 0.9) text(g, "CLICK or SPACE for next level", cx, 390, F_UI, alpha(Color.WHITE, blink)); break; case OVER:
                    g.setColor(new Color(0, 0, 0, (int) (170 * fade))); g.fillRect(0, 0, W, H); glowText(g, "GAME OVER", cx, 250, F_BIG, new Color(255, 80, 100)); text(g, "SCORE  " + String.format("%,d", score), cx, 320, F_MID, Color.WHITE);
                    if (newHigh) glowText(g, "NEW HIGH SCORE!", cx, 365, F_UI, gold); else text(g, "BEST  " + String.format("%,d", hiScore), cx, 365, F_UI, gold);
                    text(g, "Level " + level + "   |   Bricks broken " + broken + "   |   Best combo x" + bestCombo, cx, 410, F_SM, pale);
                    if (stateTime > 0.9) text(g, "CLICK or SPACE to play again", cx, 470, F_UI, alpha(Color.WHITE, blink)); break; default: break; } } }
    static class Sfx {
        boolean enabled = true, available = true;
        static final AudioFormat FMT = new AudioFormat(22050f, 16, 1, true, false);
        static final int[] SCALE = { 0, 2, 4, 7, 9, 12, 14, 16, 19, 21, 24, 26, 28 };
        final Map<String, byte[]> cache = new ConcurrentHashMap<>();
        final ExecutorService ex = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "sfx"); t.setDaemon(true); return t; });
        /** kind = sound name, v = variant (pitch step for bricks). */
        void play(String kind, int v) { if (!enabled || !available) return; ex.submit(() -> { try {
                    byte[] d = cache.computeIfAbsent(kind + v, k -> make(kind, v)); Clip c = AudioSystem.getClip(); c.open(FMT, d, 0, d.length);
                    c.addLineListener(e -> { if (e.getType() == LineEvent.Type.STOP) c.close(); }); c.start(); } catch (Throwable t) { available = false; } }); }
        /** wave: 0 sine, 1 square, 2 noise; frequency glides f0 -> f1. */
        static byte[] tone(double f0, double f1, double dur, int wave, double vol) { int n = (int) (22050 * dur); byte[] b = new byte[n * 2]; double ph = 0;
            for (int i = 0; i < n; i++) { double t = i / (double) n; ph += 2 * Math.PI * (f0 + (f1 - f0) * t) / 22050;
                double s = wave == 0 ? Math.sin(ph) : wave == 1 ? Math.signum(Math.sin(ph)) * 0.6 : Math.random() * 2 - 1;
                short v = (short) (s * Math.min(1, i / 120.0) * Math.pow(1 - t, 1.6) * vol * 32767); b[2 * i] = (byte) v; b[2 * i + 1] = (byte) (v >> 8); } return b; }
        static byte[] join(byte[]... p) { int n = 0; for (byte[] x : p) n += x.length; byte[] r = new byte[n]; int o = 0; for (byte[] x : p) { System.arraycopy(x, 0, r, o, x.length); o += x.length; } return r; }
        static byte[] make(String kind, int v) { double f = 440 * Math.pow(2, SCALE[Math.min(v, 12)] / 12.0); switch (kind) {
                case "paddle": return tone(330, 200, 0.08, 0, 0.5);   case "wall": return tone(240, 200, 0.04, 0, 0.25);   case "launch": return tone(300, 700, 0.12, 0, 0.4);
                case "brick": return tone(f, f * 0.9, 0.09, 1, 0.35);   case "hit": return tone(300 + 60 * v, 260, 0.06, 1, 0.3);   case "clang": return tone(190, 150, 0.09, 1, 0.3);
                case "bomb": return join(tone(0, 0, 0.28, 2, 0.45), tone(90, 40, 0.25, 0, 0.5));   case "laser": return tone(1000, 250, 0.1, 1, 0.25);   case "lose": return tone(420, 90, 0.55, 0, 0.5);
                case "power": return join(tone(520, 520, 0.06, 0, 0.4), tone(660, 660, 0.06, 0, 0.4), tone(880, 880, 0.1, 0, 0.4));
                case "clear": return join(tone(523, 523, 0.1, 0, 0.4), tone(659, 659, 0.1, 0, 0.4), tone(784, 784, 0.1, 0, 0.4), tone(1046, 1046, 0.25, 0, 0.4));
                default: return tone(440, 440, 0.05, 0, 0.3); } } } }
