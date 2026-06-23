#!/bin/bash

CERTS_DIR="./config/certs/"

# Créer le répertoire des certificats
mkdir -p $CERTS_DIR

# Générer une clé privée pour le serveur
openssl genrsa -out ${CERTS_DIR}server.key 2048

# Générer un certificat auto-signé pour le serveur
openssl req -new -x509 -key ${CERTS_DIR}server.key \
    -out ${CERTS_DIR}server.crt -days 365 \
    -subj "/C=CH/ST=GE/L=Geneva/O=UBP/OU=IT/CN=localhost"

# Générer un keystore server PKCS12
keytool -genkeypair -alias server -keyalg RSA -keysize 2048 \
    -keystore ${CERTS_DIR}serverkeystore.p12 -storetype PKCS12 \
    -storepass changeit -keypass changeit \
    -dname "CN=proxy-client,OU=IT,O=UBP,L=Geneva,ST=GE,C=CH"

# Générer un truststore
keytool -genkeypair -alias ca -keyalg RSA -keysize 2048 \
    -keystore ${CERTS_DIR}truststore.jks \
    -storepass changeit -keypass changeit \
    -dname "CN=proxy-trust-CA,OU=IT,O=UBP,L=Geneva,ST=GE,C=CH"

echo "Certificates generated into folder: ${CERTS_DIR}"