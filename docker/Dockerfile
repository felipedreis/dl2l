FROM openjdk:23-jdk AS run

RUN microdnf install nc

WORKDIR /dl2l/run

COPY simulations/ ./simulations/
COPY target/*-wd.jar ./dl2l.jar
COPY scripts/run-dl2l.sh ./

ENV HOST "localhost"
ENV PORT "2551"
ENV ROLE ""
ENV DATA_DIR "/dl2l/data"
ENV SIMULATION ""
EXPOSE $PORT

ENTRYPOINT ./run-dl2l.sh ${HOST} ${PORT} ${ROLE} ${DATA_DIR} ${SIMULATION} /config/docker-config.conf
