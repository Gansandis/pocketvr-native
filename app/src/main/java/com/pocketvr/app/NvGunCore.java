package com.pocketvr.app;

import java.util.ArrayList;
import java.util.Random;

/**
 * The rules of the shooter, with NO Android classes in it (so it can be tested on any computer and moved between projects as it is).
 * World: Z up, metres. Enemies appear on a ring around the player and walk straight at the player, wherever the player has gone.
 *
 * Every frame the host calls tick() with: the time step, where the eyes are, the aim direction (unit vector, from the head or from the hand)
 * and whether the trigger is held. The core does the rest: cool-down, reloading, the hit-scan, damage, waves, score.
 * What happens is reported to the Listener (sounds, sparks, vibration are the host's business).
 */
public final class NvGunCore {
    public static final int TITLE = 0, PLAYING = 1, OVER = 2;
    public static final int GRUNT = 0, RUNNER = 1, TANK = 2, DRONE = 3, BOSS = 4;
    public static final int PISTOL = 0, RIFLE = 1;
    public static final float EYE_H = 1.6f;                   // the eyes are this far above the floor

    // ---- weapons ----
    public static final String[] W_NAME = {"ปืนพก", "ปืนกล"};
    public static final float[] W_DMG = {2f, 1f};
    public static final float[] W_COOL = {0.20f, 0.085f};
    public static final float[] W_RELOAD = {1.2f, 2.2f};
    public static final int[] W_MAG = {12, 60};
    public static final boolean[] W_AUTO = {false, true};

    public static final class Enemy {
        public int type; public boolean boss;
        public float x, y;                     // where it stands in the world (metres)
        public float dist;                     // how far it is from you right now (kept up to date by tick)
        public float hp, maxHp, speed, scale;
        public float flash, wob, ph, atk;      // hit flash (s), walk phase, a random phase, time until the next blow
        public boolean attacking;
    }

    /** What the last tick's trigger did. result: 0 miss, 1 body, 2 head. */
    public static final class Shot {
        public boolean fired, dry, kill;
        public int result;
        public Enemy enemy;                    // who was hit (also set when it died)
        public float hx, hy, hz;               // where the bullet ended (a hit point, or 40 m away when it missed)
        public float dx, dy, dz;               // the direction it went
    }

    public interface Listener {
        default void onShot(Shot s) {}
        default void onDry() {}
        default void onHit(Enemy e, boolean head, boolean kill) {}
        default void onHurt(float fromAz, int dmg) {}
        default void onReload() {}
        default void onReloaded() {}
        default void onWave(int wave, boolean boss) {}
        default void onGameOver(int score) {}
    }

    public Listener listener = new Listener() {};
    public final ArrayList<Enemy> enemies = new ArrayList<>();
    public final Shot shot = new Shot();
    public static int best = 0;

    // ---- state (read by the HUD) ----
    public int state = TITLE, hp = 100, score = 0, wave = 0, combo = 0, kills = 0;
    public int maxHp = 100;
    public int weapon = PISTOL;
    public final int[] ammo = {W_MAG[0], W_MAG[1]};
    public boolean rifleUnlocked = false;
    public float reloadT = 0f, bannerT = 0f;   // reloadT: seconds left of the reload (0 = not reloading)
    public boolean bannerBoss = false;
    public float assistDeg = 1.6f;             // how far off a shot may be and still count (the aim of a head or a hand is shaky)

    public int startWeapon = PISTOL;           // the weapon you start with (the hand version starts with the machine gun)
    public boolean lockWeapon = false;         // true = no weapon switching
    public float ox, oy, oz, heading, floorZ;  // where the game began (the arena's centre), which way "front" was, and the floor's height
    public float px, py;                       // where the player is now (the eyes, updated by tick)
    private final Random rnd;
    private float cool = 0f, comboT = 0f, spawnT = 0f;
    private int toSpawn = 0, bossLeft = 0;
    private boolean prevTrigger = false;

    public NvGunCore() { this(System.nanoTime()); }
    public NvGunCore(long seed) { rnd = new Random(seed); }

    // ================= control =================
    public void start(float x, float y, float z, float headingRad) {
        ox = x; oy = y; oz = z; heading = headingRad; floorZ = z - EYE_H; px = x; py = y;
        enemies.clear();
        hp = maxHp; score = 0; wave = 0; combo = 0; kills = 0; comboT = 0f;
        weapon = startWeapon; rifleUnlocked = startWeapon == RIFLE;
        ammo[0] = W_MAG[0]; ammo[1] = W_MAG[1];
        reloadT = 0f; cool = 0.3f; prevTrigger = true;      // the trigger that pressed "start" must not fire
        shot.fired = false;
        state = PLAYING;
        nextWave();
    }

    public void stop() { state = TITLE; enemies.clear(); reloadT = 0f; }

    public boolean switchWeapon() {
        if (state != PLAYING || lockWeapon || !rifleUnlocked || reloadT > 0f) return false;
        weapon = 1 - weapon; cool = 0.25f;
        return true;
    }

    public void reload() {
        if (state != PLAYING || reloadT > 0f || ammo[weapon] >= W_MAG[weapon]) return;
        reloadT = W_RELOAD[weapon];
        listener.onReload();
    }

    /** a finished reload fraction 0..1 (for the HUD) */
    public float reloadProgress() { return reloadT <= 0f ? 1f : 1f - reloadT / W_RELOAD[weapon]; }

    // ================= enemies =================
    /** the direction of the enemy as seen from the player (radians, 0 = +X) */
    public float azOf(Enemy e) { return (float) Math.atan2(e.y - py, e.x - px); }
    public float xOf(Enemy e) { return e.x; }
    public float yOf(Enemy e) { return e.y; }
    /** the height of its feet (a drone hovers) */
    public float zOf(Enemy e) { return floorZ + (e.type == DRONE ? 1.5f + 0.15f * (float) Math.sin(e.wob * 2.2f + e.ph) : 0f); }

    /** an enemy `dist` metres from the player, in direction `az` (radians) */
    public Enemy spawn(int type, float az, float dist) {
        Enemy e = new Enemy();
        e.type = type; e.boss = type == BOSS; e.dist = dist; e.ph = rnd.nextFloat() * 6.28f;
        e.x = px + dist * (float) Math.cos(az); e.y = py + dist * (float) Math.sin(az);
        float k = Math.min(1.8f, 1f + 0.04f * wave);
        switch (type) {
            case RUNNER: e.maxHp = 4; e.speed = 2.1f * k; e.scale = 0.9f; break;
            case TANK: e.maxHp = 24; e.speed = 0.6f * k; e.scale = 1.35f; break;
            case DRONE: e.maxHp = 4; e.speed = 1.3f * k; e.scale = 1f; break;
            case BOSS: e.maxHp = 60 + 8 * wave; e.speed = 0.5f; e.scale = 2.0f; break;
            default: e.maxHp = 6; e.speed = 0.9f * k; e.scale = 1f; break;
        }
        e.hp = e.maxHp;
        enemies.add(e);
        return e;
    }

    private int pickType() {
        int r = rnd.nextInt(100);
        if (wave >= 4 && r < 12) return TANK;
        if (wave >= 3 && r < 32) return DRONE;
        if (wave >= 2 && r < 55) return RUNNER;
        return GRUNT;
    }

    private void nextWave() {
        wave++;
        boolean boss = wave % 5 == 0;
        bossLeft = boss ? 1 + wave / 5 : 0;
        toSpawn = boss ? bossLeft + 3 + wave / 2 : 4 + wave * 2;
        spawnT = 1.2f; bannerT = 2.2f; bannerBoss = boss;
        if (wave > 1) hp = Math.min(maxHp, hp + 15);
        if (wave >= 2) rifleUnlocked = true;
        listener.onWave(wave, boss);
    }

    private void spawnNext() {
        float spread = Math.min((float) Math.PI, (float) Math.toRadians(70) + wave * 0.4f);   // early waves come from in front, later ones from all around
        float az = heading + (rnd.nextFloat() * 2f - 1f) * spread;
        if (bossLeft > 0) { bossLeft--; spawn(BOSS, az, 11f); }
        else spawn(pickType(), az, 9.5f + rnd.nextFloat() * 2f);
    }

    private static int dmgOf(Enemy e) {
        switch (e.type) { case RUNNER: return 6; case TANK: return 16; case DRONE: return 5; case BOSS: return 28; default: return 8; }
    }
    private static int pointsOf(Enemy e) {
        switch (e.type) { case RUNNER: return 15; case TANK: return 40; case DRONE: return 20; case BOSS: return 200; default: return 10; }
    }

    private void hurt(int dmg, float fromAz) {
        hp -= dmg; combo = 0;
        listener.onHurt(fromAz, dmg);
        if (hp <= 0) {
            hp = 0; state = OVER; if (score > best) best = score;
            enemies.clear(); reloadT = 0f;
            listener.onGameOver(score);
        }
    }

    // ================= the frame =================
    /** one step. (ex,ey,ez) = the eyes, (dx,dy,dz) = the aim direction (does not have to be a unit vector), trigger = the trigger is held right now */
    public void tick(float dt, float ex, float ey, float ez, float dx, float dy, float dz, boolean trigger) {
        shot.fired = false; shot.dry = false; shot.kill = false; shot.result = 0; shot.enemy = null;
        if (dt > 0.05f) dt = 0.05f;
        px = ex; py = ey;
        if (bannerT > 0f) bannerT = Math.max(0f, bannerT - dt);
        if (state != PLAYING) { prevTrigger = trigger; return; }
        if (cool > 0f) cool -= dt;
        if (comboT > 0f) { comboT -= dt; if (comboT <= 0f) combo = 0; }
        if (reloadT > 0f) {
            reloadT -= dt;
            if (reloadT <= 0f) { reloadT = 0f; ammo[weapon] = W_MAG[weapon]; listener.onReloaded(); }
        }

        // trigger
        boolean edge = trigger && !prevTrigger;
        prevTrigger = trigger;
        boolean want = W_AUTO[weapon] ? trigger : edge;
        if (want && cool <= 0f && reloadT <= 0f) {
            if (ammo[weapon] > 0) fire(ex, ey, ez, dx, dy, dz);
            else if (edge) { shot.dry = true; cool = 0.25f; listener.onDry(); reload(); }
        }

        // enemies
        for (int i = enemies.size() - 1; i >= 0 && state == PLAYING; i--) {
            if (i >= enemies.size()) continue;
            Enemy e = enemies.get(i);
            e.wob += dt;
            if (e.flash > 0f) e.flash -= dt;
            float ddx = px - e.x, ddy = py - e.y;
            float d = (float) Math.sqrt(ddx * ddx + ddy * ddy);
            e.dist = d;
            float stop = 1.2f + 0.25f * e.scale;
            if (d > stop) {
                float ux = ddx / d, uy = ddy / d;
                float weave = 0f;                                              // runners zigzag, drones sway
                if (e.type == RUNNER) weave = (float) Math.sin(e.wob * 3f + e.ph) * 0.9f * Math.min(1f, d / 3f);
                else if (e.type == DRONE) weave = (float) Math.sin(e.wob * 1.6f + e.ph) * 0.7f * Math.min(1f, d / 4f);
                e.x += (ux * e.speed - uy * weave) * dt;
                e.y += (uy * e.speed + ux * weave) * dt;
                e.attacking = false;
            } else {
                e.attacking = true;
                e.atk -= dt;
                if (e.atk <= 0f) { e.atk = e.boss ? 1.1f : 0.9f; hurt(dmgOf(e), azOf(e)); }
            }
        }
        if (state != PLAYING) return;

        // waves
        if (bannerT <= 0f && toSpawn > 0) {
            spawnT -= dt;
            if (spawnT <= 0f) { spawnNext(); toSpawn--; spawnT = Math.max(0.5f, 1.7f - wave * 0.06f) + rnd.nextFloat() * 0.5f; }
        }
        if (toSpawn == 0 && enemies.isEmpty() && bannerT <= 0f) nextWave();
    }

    // ================= the hit-scan =================
    private void fire(float ex, float ey, float ez, float dx, float dy, float dz) {
        float l = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (l < 1e-5f) return;
        dx /= l; dy /= l; dz /= l;
        ammo[weapon]--;
        cool = W_COOL[weapon];
        Enemy hit = scan(ex, ey, ez, dx, dy, dz);
        boolean head = scanHead; float bestT = scanT;

        Shot sh = shot;
        sh.fired = true; sh.dx = dx; sh.dy = dy; sh.dz = dz;
        float reach = hit != null ? bestT : 40f;
        sh.hx = ex + dx * reach; sh.hy = ey + dy * reach; sh.hz = ez + dz * reach;
        sh.result = 0;
        if (hit != null) {
            sh.result = head ? 2 : 1; sh.enemy = hit;
            hit.hp -= W_DMG[weapon] * (head ? 2f : 1f);
            hit.flash = 0.2f;
            if (!hit.boss && hit.type != TANK) {                                   // a little knock-back
                float kd = Math.max(0.3f, hit.dist);
                hit.x += (hit.x - px) / kd * 0.25f; hit.y += (hit.y - py) / kd * 0.25f; hit.dist += 0.25f;
            }
            if (hit.hp <= 0f) {
                sh.kill = true;
                enemies.remove(hit);
                kills++;
                combo = comboT > 0f ? combo + 1 : 1; comboT = 2.5f;
                score += Math.round(pointsOf(hit) * (1f + 0.1f * Math.min(combo - 1, 9))) + (head ? 5 : 0);
            }
            listener.onHit(hit, head, sh.kill);
        }
        listener.onShot(sh);
        if (ammo[weapon] == 0) reload();
    }

    // ---- what the ray meets first (the same test is used by fire() and by peek()) ----
    private boolean scanHead = false;
    private float scanT = 0f;

    private Enemy scan(float ex, float ey, float ez, float dx, float dy, float dz) {
        float tan = (float) Math.tan(Math.toRadians(assistDeg));
        Enemy hit = null; boolean head = false; float bestT = 1e9f;
        for (Enemy e : enemies) {
            float s = e.scale;
            float cx = xOf(e), cy = yOf(e), cz = zOf(e);
            if (e.type == DRONE) {
                float t = sphere(ex, ey, ez, dx, dy, dz, cx, cy, cz + 0.4f, 0.38f, tan);
                if (t >= 0f && t < bestT) { bestT = t; hit = e; head = false; }
                continue;
            }
            float th = sphere(ex, ey, ez, dx, dy, dz, cx, cy, cz + 1.62f * s, 0.21f * s, tan * 0.7f);
            if (th >= 0f && th < bestT) { bestT = th; hit = e; head = true; continue; }
            float tb = sphere(ex, ey, ez, dx, dy, dz, cx, cy, cz + 1.0f * s, 0.50f * s, tan);
            float tl = sphere(ex, ey, ez, dx, dy, dz, cx, cy, cz + 0.45f * s, 0.40f * s, tan);
            float t = tb < 0f ? tl : (tl < 0f ? tb : Math.min(tb, tl));
            if (t >= 0f && t < bestT) { bestT = t; hit = e; head = false; }
        }
        scanHead = head; scanT = bestT;
        return hit;
    }

    /** what a shot along this ray would hit right now, without firing: 0 nothing, 1 body, 2 head (the crosshair turns red) */
    public int peek(float ex, float ey, float ez, float dx, float dy, float dz) {
        if (state != PLAYING || enemies.isEmpty()) return 0;
        float l = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (l < 1e-5f) return 0;
        return scan(ex, ey, ez, dx / l, dy / l, dz / l) == null ? 0 : (scanHead ? 2 : 1);
    }

    /** distance along the ray to a sphere (its radius grows with the distance by `tan`, the aim help); -1 = missed */
    private static float sphere(float ox, float oy, float oz, float dx, float dy, float dz, float cx, float cy, float cz, float r, float tan) {
        float px = cx - ox, py = cy - oy, pz = cz - oz;
        float tca = px * dx + py * dy + pz * dz;
        if (tca < 0.1f) return -1f;
        float d2 = px * px + py * py + pz * pz - tca * tca;
        float re = r + tca * tan;
        if (d2 > re * re) return -1f;
        float thc = (float) Math.sqrt(Math.max(0f, r * r - Math.min(d2, r * r)));
        return Math.max(0.1f, tca - thc);
    }
}
