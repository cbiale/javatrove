/*
 * Copyright 2016 Andres Almiray
 *
 * This file is part of Java Trove Examples
 *
 * Java Trove Examples is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Java Trove Examples is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Java Trove Examples. If not, see <http://www.gnu.org/licenses/>.
 */
package org.kordamp.javatrove.example08.client.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.kordamp.javatrove.example08.Command;
import org.kordamp.javatrove.example08.client.ChatClient;
import org.kordamp.javatrove.example08.client.ClientCommandDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zeromq.ZMQ;

import javax.inject.Inject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.kordamp.javatrove.example08.Command.Type.LOGIN;
import static org.kordamp.javatrove.example08.Command.Type.LOGOUT;
import static org.kordamp.javatrove.example08.Command.Type.MESSAGE;
import static org.zeromq.ZMQ.NOBLOCK;

/**
 * @author Andres Almiray
 */
public class ChatClientImpl implements ChatClient {
    private static final Logger LOG = LoggerFactory.getLogger(ChatClientImpl.class);
    private static final String UNEXPECTED_ERROR = "Unexpected error";
    private static final String TCP_PREFIX = "tcp://";
    private static final String SEMICOLON = ":";

    @Inject private ExecutorService executorService;
    @Inject private ClientCommandDispatcher clientCommandDispatcher;
    @Inject private ObjectMapper objectMapper;

    private ZMQ.Socket client;
    private ZMQ.Socket subscriber;
    private ZMQ.Context context;
    private final AtomicBoolean running = new AtomicBoolean();

    @Override
    public void login(int timeout, String server, int port, String name) {
        try {
            context = ZMQ.context(2);
            subscriber = context.socket(ZMQ.SUB);
            client = context.socket(ZMQ.REQ);

            subscriber.setRcvHWM(0);
            subscriber.connect(TCP_PREFIX + server + SEMICOLON + (port + 1));
            subscriber.subscribe("".getBytes());

            client.connect(TCP_PREFIX + server + SEMICOLON + port);

            running.set(true);
            executorService.submit(this::handleIncomingMessages);

            client.send(objectMapper.writeValueAsBytes(Command.builder()
                .type(LOGIN)
                .payload(name)
                .build()));
            client.recv(NOBLOCK);
        } catch (Exception e) {
            LOG.error(UNEXPECTED_ERROR, e);
            throw new IllegalStateException(e);
        }
    }

    private void handleIncomingMessages() {
        while (running.get()) {
            try {
                byte[] bytes = subscriber.recv();
                Command command = objectMapper.readValue(bytes, Command.class);
                clientCommandDispatcher.dispatch(command);
            } catch (Exception e) {
                LOG.error(UNEXPECTED_ERROR, e);
                terminate();
            }
        }
    }

    @Override
    public void logout(String name) {
        try {
            client.send(objectMapper.writeValueAsBytes(Command.builder()
                .type(LOGOUT)
                .payload(name)
                .build()));
            client.recv(NOBLOCK);
        } catch (Exception e) {
            LOG.error(UNEXPECTED_ERROR, e);
        }
        terminate();
    }

    @Override
    public void send(String message) {
        try {
            client.send(objectMapper.writeValueAsBytes(Command.builder()
                .type(MESSAGE)
                .payload(message)
                .build()));
            client.recv(NOBLOCK);
        } catch (Exception e) {
            LOG.error(UNEXPECTED_ERROR, e);
            terminate();
            throw new IllegalStateException(e);
        }
    }

    private void terminate() {
        running.set(false);

        subscriber.close();
        client.close();
        context.term();
    }
}
