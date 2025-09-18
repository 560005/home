FROM alpine:latest

# Install nginx
RUN apk add --no-cache nginx

# Create nginx user and group
RUN adduser -D -s /bin/sh nginx

# Install Zola
RUN apk add --no-cache wget tar
RUN wget -O zola.tar.gz https://github.com/getzola/zola/releases/download/v0.18.0/zola-v0.18.0-x86_64-unknown-linux-musl.tar.gz
RUN tar -xzf zola.tar.gz
RUN mv zola /usr/local/bin/
RUN chmod +x /usr/local/bin/zola

# Copy site files
COPY . /app
WORKDIR /app

# Build the site
RUN zola build

# Setup nginx
RUN mkdir -p /var/lib/nginx/logs
RUN touch /var/lib/nginx/logs/error.log
RUN mkdir -p /run/nginx

# Copy built site to nginx directory
RUN cp -r /app/public/* /var/lib/nginx/html/ || mkdir -p /var/lib/nginx/html

# Create nginx config
RUN echo 'server { \
    listen 8080; \
    server_name _; \
    root /var/lib/nginx/html; \
    index index.html; \
    location / { \
        try_files $uri $uri/ /index.html; \
    } \
}' > /etc/nginx/http.d/default.conf

# Remove default nginx config
RUN rm -f /etc/nginx/http.d/default.conf
RUN echo 'events { worker_connections 1024; } \
http { \
    include /etc/nginx/mime.types; \
    default_type application/octet-stream; \
    sendfile on; \
    keepalive_timeout 65; \
    server { \
        listen 8080; \
        server_name _; \
        root /var/lib/nginx/html; \
        index index.html; \
        location / { \
            try_files $uri $uri/ /index.html; \
        } \
    } \
}' > /etc/nginx/nginx.conf

EXPOSE 8080

CMD ["nginx", "-g", "daemon off;"]
