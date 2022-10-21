FROM openjdk:14-jdk AS run

WORKDIR /dl2l/run

COPY config/docker-config.conf ./
COPY simulations ./simulations/
COPY target/*-wd.jar ./dl2l.jar
COPY scripts/run-dl2l.sh ./

ENV HOST ""
ENV PORT ""
ENV ROLE ""
ENV DATA_DIR "/dl2l/data"
ENV SIMULATION ""

ENTRYPOINT ./run-dl2l.sh ${HOST} ${PORT} ${ROLE} ${DATA_DIR} ${SIMULATION}
