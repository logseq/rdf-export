FROM openjdk:24-slim-bullseye
RUN apt update && apt remove -y cmdtest && apt install -y npm curl ca-certificates gnupg

# Install Clojure
RUN curl -L -O https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh && chmod +x linux-install.sh && ./linux-install.sh 

# Install Babashka
RUN ["/bin/bash", "-c", "bash < <(curl -s https://raw.githubusercontent.com/babashka/babashka/master/install)"]

# Install Node.js (version 14 or higher)
ENV NODE_MAJOR=20  
RUN mkdir -p /etc/apt/keyrings  
RUN curl -fsSL https://deb.nodesource.com/gpgkey/nodesource-repo.gpg.key | gpg --dearmor -o /etc/apt/keyrings/nodesource.gpg
RUN echo "deb [signed-by=/etc/apt/keyrings/nodesource.gpg] https://deb.nodesource.com/node_$NODE_MAJOR.x nodistro main" | tee /etc/apt/sources.list.d/nodesource.list
RUN apt update && apt install nodejs -y

WORKDIR /logseq-rdf-export
COPY . /logseq-rdf-export
RUN npm install -g yarn
RUN yarn install
RUN yarn global add /logseq-rdf-export
RUN logseq-rdf-export || true

WORKDIR /data