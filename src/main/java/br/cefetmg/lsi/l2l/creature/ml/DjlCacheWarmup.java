package br.cefetmg.lsi.l2l.creature.ml;

import ai.djl.engine.Engine;

/**
 * Build-time-only entry point: forces DJL's PyTorch engine to initialize, which
 * downloads its native library and JNI bridge (libdjl_torch.so) into
 * $DJL_CACHE_DIR. Run once during the Docker image build (which has internet
 * access) so the running container never needs to reach publish.djl.ai itself —
 * required on hosts with no outbound internet (e.g. CCAD compute nodes).
 */
public final class DjlCacheWarmup {

    private DjlCacheWarmup() {
    }

    public static void main(String[] args) {
        Engine.getEngine("PyTorch");
        System.out.println("DJL PyTorch engine cache warmed at " + System.getenv("DJL_CACHE_DIR"));
    }
}
