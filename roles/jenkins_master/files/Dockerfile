FROM jenkins/jenkins:lts
USER root
RUN apt-get update && \
    apt-get install -y sssd sudo && \
    apt-get install -y supervisor && \
    apt-get -y clean autoremove && \
    rm -rf /var/lib/apt/lists/*
COPY plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN /usr/local/bin/install-plugins.sh < /usr/share/jenkins/ref/plugins.txt
RUN curl https://bootstrap.pypa.io/get-pip.py -o get-pip.py && \
    python get-pip.py && \
     pip install ansible openshift kubernetes-validate

RUN apt-get update && \
    apt-get install -y wget libltdl7 apt-transport-https ca-certificates curl gnupg2 software-properties-common && \
    wget https://download.docker.com/linux/debian/dists/stretch/pool/stable/amd64/docker-ce-cli_18.09.0~3-0~debian-stretch_amd64.deb && \
    dpkg -i docker-ce-cli_18.09.0~3-0~debian-stretch_amd64.deb

RUN curl -LO https://storage.googleapis.com/kubernetes-release/release/$(curl -s https://storage.googleapis.com/kubernetes-release/release/stable.txt)/bin/linux/amd64/kubectl \
        && chmod +x ./kubectl \
        && mv ./kubectl /usr/local/bin/kubectl

RUN apt-get install -y openssh-server && /etc/init.d/ssh start &&\
    ssh-keygen -b 2048 -t rsa -f ~/.ssh/id_rsa -q -N "" && \
    cat ~/.ssh/id_rsa.pub >> ~/.ssh/authorized_keys


