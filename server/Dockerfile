# 构建阶段：基础镜像走 daocloud 国内镜像加速，Go 依赖走 goproxy.cn
FROM m.daocloud.io/docker.io/library/golang:1.22-alpine AS builder

ENV GOPROXY=https://goproxy.cn,direct \
    GOSUMDB=sum.golang.org \
    CGO_ENABLED=0

WORKDIR /src

COPY go.mod go.sum ./
RUN go mod download

COPY . .
RUN go build -trimpath -ldflags="-s -w" -o /out/schedule-server .

# 运行阶段：alpine 换国内 apk 源，装时区与证书
FROM m.daocloud.io/docker.io/library/alpine:3.19

RUN sed -i 's#dl-cdn.alpinelinux.org#mirrors.aliyun.com#g' /etc/apk/repositories \
    && apk add --no-cache tzdata ca-certificates

ENV TZ=Asia/Shanghai
WORKDIR /app
COPY --from=builder /out/schedule-server .

EXPOSE 8080
CMD ["/app/schedule-server"]
