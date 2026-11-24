FROM mysql:8.4 AS mysql-client
RUN cp -L /lib64/libncurses.so.6 /libncurses.so.6

FROM python:3.12-slim
COPY --from=mysql-client /usr/bin/mysql /usr/local/bin/mysql
COPY --from=mysql-client /libncurses.so.6 /usr/local/lib/libncurses.so.6
RUN ldconfig && mysql --version
COPY archive.py /app/archive.py
USER 65532:65532
ENTRYPOINT ["python3", "/app/archive.py"]
