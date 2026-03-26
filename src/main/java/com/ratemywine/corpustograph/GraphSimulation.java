package com.ratemywine.corpustograph;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class GraphSimulation {
    public static final double REST_LENGTH = 110.0;
    private final List<Particle> particles = new ArrayList<>();
    private final double[][] similarity;
    private final Random random = new Random();
    private int width;
    private int height;

    public GraphSimulation(List<DocumentData> docs, double[][] similarity, int width, int height) {
        this.similarity = similarity;
        this.width = Math.max(500, width);
        this.height = Math.max(400, height);
        initParticles(docs);
    }

    private void initParticles(List<DocumentData> docs) {
        for (int i = 0; i < docs.size(); i++) {
            DocumentData doc = docs.get(i);
            double radius = 8.0 + Math.min(26.0, Math.sqrt(doc.tokenCount()) * 0.6);
            double x = 80 + random.nextDouble() * Math.max(200, width - 160);
            double y = 80 + random.nextDouble() * Math.max(200, height - 160);
            double mass = Math.max(1.0, doc.tokenCount() / 40.0);
            particles.add(new Particle(i, x, y, mass, radius));
        }
    }

    public void step(double dt, double speedMultiplier) {
        if (particles.isEmpty()) {
            return;
        }
        double effectiveDt = dt * speedMultiplier;
        double springBase = 1.2;
        double repulsion = 8000.0;
        double damping = 0.88;

        double[] fx = new double[particles.size()];
        double[] fy = new double[particles.size()];

        for (int i = 0; i < particles.size(); i++) {
            for (int j = i + 1; j < particles.size(); j++) {
                Particle a = particles.get(i);
                Particle b = particles.get(j);
                double dx = b.x - a.x;
                double dy = b.y - a.y;
                double distSq = dx * dx + dy * dy + 0.01;
                double dist = Math.sqrt(distSq);
                double nx = dx / dist;
                double ny = dy / dist;

                double sim = similarity[i][j];
                double k = springBase * sim;
                double springForce = k * (dist - REST_LENGTH);

                double repelForce = repulsion / distSq;
                double total = springForce - repelForce;

                fx[i] += total * nx;
                fy[i] += total * ny;
                fx[j] -= total * nx;
                fy[j] -= total * ny;
            }
        }

        for (int i = 0; i < particles.size(); i++) {
            Particle p = particles.get(i);
            double ax = fx[i] / p.mass;
            double ay = fy[i] / p.mass;

            p.vx = (p.vx + ax * effectiveDt) * damping;
            p.vy = (p.vy + ay * effectiveDt) * damping;
            p.x += p.vx * effectiveDt;
            p.y += p.vy * effectiveDt;

            keepInsideBounds(p);
        }
    }

    public void resize(int width, int height) {
        this.width = Math.max(500, width);
        this.height = Math.max(400, height);
    }

    private void keepInsideBounds(Particle p) {
        double margin = p.radius + 8;
        if (p.x < margin) {
            p.x = margin;
            p.vx *= -0.4;
        }
        if (p.y < margin) {
            p.y = margin;
            p.vy *= -0.4;
        }
        if (p.x > width - margin) {
            p.x = width - margin;
            p.vx *= -0.4;
        }
        if (p.y > height - margin) {
            p.y = height - margin;
            p.vy *= -0.4;
        }
    }

    public List<Particle> particles() {
        return particles;
    }

    public double[][] similarity() {
        return similarity;
    }

    public Particle findParticle(Point2D point) {
        for (Particle p : particles) {
            double dx = point.getX() - p.x;
            double dy = point.getY() - p.y;
            if (dx * dx + dy * dy <= p.radius * p.radius) {
                return p;
            }
        }
        return null;
    }

    public static class Particle {
        public final int index;
        public final double mass;
        public final double radius;
        public double x;
        public double y;
        public double vx;
        public double vy;

        public Particle(int index, double x, double y, double mass, double radius) {
            this.index = index;
            this.x = x;
            this.y = y;
            this.mass = mass;
            this.radius = radius;
        }
    }
}
