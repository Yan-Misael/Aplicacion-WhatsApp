#!/bin/bash

# Terminate any existing java instances just in case
pkill -f 'whatsapp.server.core.ServerNode' || true

# 1. Start nodes
java -cp 'target/Aplicacion-WhatsApp-1.0-SNAPSHOT.jar' whatsapp.server.core.ServerNode config/node1.properties > n1.log 2>&1 &
PID1=$!
java -cp 'target/Aplicacion-WhatsApp-1.0-SNAPSHOT.jar' whatsapp.server.core.ServerNode config/node2.properties > n2.log 2>&1 &
PID2=$!
java -cp 'target/Aplicacion-WhatsApp-1.0-SNAPSHOT.jar' whatsapp.server.core.ServerNode config/node3.properties > n3.log 2>&1 &
PID3=$!

echo "Nodos iniciados, esperando estabilizacion (10s)..."
sleep 10

# 2. Start clients with piped input

# Client A (felipe) on Node 1
(
  echo "/login felipe"
  sleep 2
  echo "/msg luis Hola Luis, te escribo desde el Nodo 1"
  sleep 2
  echo "/creargrupo amigos"
  sleep 1
  echo "/gmsg amigos Bienvenidos al grupo amigos!"
  sleep 5
  echo "/salir"
) | java -cp 'target/Aplicacion-WhatsApp-1.0-SNAPSHOT.jar' whatsapp.client.ClienteNodo localhost 5001 > c1.log 2>&1 &
CPID1=$!

# Client B (luis) on Node 2
(
  sleep 1
  echo "/login luis"
  sleep 2
  echo "/msg maria Hola Maria, mensaje desde el Nodo 2"
  sleep 1
  echo "/unirse amigos"
  sleep 6
  echo "/salir"
) | java -cp 'target/Aplicacion-WhatsApp-1.0-SNAPSHOT.jar' whatsapp.client.ClienteNodo localhost 5002 > c2.log 2>&1 &
CPID2=$!

# Client C (maria) on Node 3
(
  sleep 2
  echo "/login maria"
  sleep 3
  echo "/unirse amigos"
  sleep 2
  echo "/gmsg amigos Hola a todos, soy Maria!"
  sleep 2
  echo "/salir"
) | java -cp 'target/Aplicacion-WhatsApp-1.0-SNAPSHOT.jar' whatsapp.client.ClienteNodo localhost 5003 > c3.log 2>&1 &
CPID3=$!

echo "Esperando que los clientes terminen..."
wait $CPID1 $CPID2 $CPID3

echo "Clientes terminados. Deteniendo nodos..."
kill $PID1 $PID2 $PID3
echo "Test finalizado."
