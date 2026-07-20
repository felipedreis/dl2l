FROM eclipse-temurin:23-jdk AS run

RUN apt-get update && apt-get install -y netcat-openbsd && rm -rf /var/lib/apt/lists/*

WORKDIR /dl2l/run

# simulations/ is deliberately NOT baked in here — it's bind-mounted onto
# ./simulations at container start (see ansible/roles/common/templates/
# docker-compose.yml.j2 and trial_runner_ccad's run_trial.sh.j2) so a new or
# edited *.conf never requires an image rebuild.
COPY target/l2l-*-wd.jar ./dl2l.jar
COPY scripts/run-dl2l.sh ./

# Fixed, absolute cache dir (not $HOME-relative) so it's found at runtime
# regardless of which uid/$HOME the container actually runs under. Warming
# it here (build time, has internet) means the running container never
# needs to reach publish.djl.ai itself — required on hosts with no outbound
# internet (e.g. CCAD compute nodes; see DjlCacheWarmup's javadoc).
ENV DJL_CACHE_DIR "/opt/djl-cache"
RUN java -cp dl2l.jar br.cefetmg.lsi.l2l.creature.ml.DjlCacheWarmup \
    && find /opt/djl-cache -type f

ENV HOST "localhost"
ENV PORT "2551"
ENV ROLE ""
ENV DATA_DIR "/dl2l/data"
ENV SIMULATION ""
EXPOSE $PORT

ENTRYPOINT ./run-dl2l.sh ${HOST} ${PORT} ${ROLE} ${DATA_DIR} ${SIMULATION} /config/docker-config.conf
