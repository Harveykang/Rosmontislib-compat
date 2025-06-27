package com.freefish.rosmontislib.client.particle.advance;

import com.freefish.rosmontislib.RosmontisLib;
import com.freefish.rosmontislib.client.particle.advance.base.IParticle;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;

import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveTask;

@MethodsReturnNonnullByDefault
@ParametersAreNonnullByDefault
public class RLParticleQueueRenderType extends RLParticleRenderType {
    public boolean isRenderingQueue() {
        return isRenderingQueue;
    }

    private static class BufferBuilderPool {
        private final ConcurrentLinkedQueue<BufferBuilder> pool = new ConcurrentLinkedQueue<>();

        public BufferBuilder acquire() {
            BufferBuilder buffer = pool.poll();
            return buffer != null ? buffer : new BufferBuilder(256);
        }

        public void release(BufferBuilder buffer) {
            pool.offer(buffer);
        }
    }

    public static final RLParticleQueueRenderType INSTANCE = new RLParticleQueueRenderType();
    private static final BufferBuilderPool BUILDER_POOL = new BufferBuilderPool();

    // runtime
    protected final Map<RLParticleRenderType, Queue<IParticle>> particles =
        RosmontisLib.isModLoaded("asyncparticles") ? new ConcurrentHashMap<>() : new HashMap<>();
    private Camera camera;
    private float pPartialTicks;
    private boolean isRenderingQueue;

    @Override
    public void begin(BufferBuilder builder) {
        particles.clear();
        camera = null;
        isRenderingQueue = false;
    }

    @Override
    public void end(BufferBuilder builder) {
        isRenderingQueue = true;
        for (var entry : particles.entrySet()) {
            var type = entry.getKey();
            var list = entry.getValue();
            if (!list.isEmpty()) {
                RenderSystem.setShader(GameRenderer::getParticleShader);
                RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
                type.prepareStatus();

                if (type.isParallel()) {
                    var forkJoinPool = ForkJoinPool.commonPool();
                    var maxThreads = ForkJoinPool.getCommonPoolParallelism() + 1;
                    var task = forkJoinPool.submit(new ParallelRenderingTask(Math.max(list.size() / maxThreads, 64),type, list.spliterator()));
                    try {
                        for (var buffer : task.get()) {
                            type.end(buffer);
                            BUILDER_POOL.release(buffer);
                        }
                    } catch (Throwable ignored) {
                        ignored.printStackTrace();
                    }
                } else {
                    type.begin(builder);
                    for (var particle : list) {
                        particle.render(builder, camera, pPartialTicks);
                    }
                    type.end(builder);
                }

                type.releaseStatus();
            }
        }
        isRenderingQueue = false;
    }

    public void pipeQueue(@Nonnull RLParticleRenderType type, @Nonnull Collection<IParticle> queue, Camera camera, float pPartialTicks) {
        particles.compute(type, (k, v) -> {
            if (v == null) {
                v = new ArrayDeque<>();
            }
            v.addAll(queue);
			return v;
		});
        if (this.camera == null) {
            this.camera = camera;
            this.pPartialTicks = pPartialTicks;
        }
    }

    class ParallelRenderingTask extends RecursiveTask<List<BufferBuilder>> {
        private final int threshold; // ForkJoin granularity threshold
        private final RLParticleRenderType type;
        private final Spliterator<IParticle> particles;

        public ParallelRenderingTask(int threshold, RLParticleRenderType type, Spliterator<IParticle> particles) {
            this.type = type;
            this.particles = particles;
            this.threshold = threshold;
        }

        @Override
        protected List<BufferBuilder> compute() {
            if(particles.estimateSize() > threshold){
                var split = particles.trySplit();
                var task1 = new ParallelRenderingTask(threshold, type, particles).fork();
                var result = new ArrayList<>(split != null ? new ParallelRenderingTask(threshold, type, split).compute() : List.of());
                result.addAll(task1.join());
                return result;
            } else {
                BufferBuilder buffer = BUILDER_POOL.acquire();
                type.begin(buffer);
                particles.forEachRemaining(p -> p.render(buffer, camera, pPartialTicks));
                return List.of(buffer);
            }
        }

    }
}