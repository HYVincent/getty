package org.getty.core.handler.ssl.sslfacade;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import java.nio.ByteBuffer;
import javax.net.ssl.SSLEngineResult;

public class SSLFacade implements ISSLFacade {
    private static final String TAG = "SSLFascade";

    private Handshaker _handshaker;
    private IHandshakeCompletedListener _hcl;
    private final Worker _worker;
    private boolean _clientMode;

    public SSLFacade(SSLContext context, boolean client,
                     boolean clientAuthRequired, ITaskHandler taskHandler) {
        //Currently there is no support for SSL session reuse,
        // so no need to take a peerHost or port from the host application
        final String who = client ? "client" : "server";
        SSLEngine engine = makeSSLEngine(context, client, clientAuthRequired);
        engine.setEnabledProtocols(new String[]{"TLSv1", "TLSv1.1",
                "TLSv1.2"});
        Buffers buffers = new Buffers(engine.getSession());
        _worker = new Worker(who, engine, buffers);
        _handshaker = new Handshaker(client, _worker, taskHandler);
        _clientMode = client;
    }

    private void debug(final String message, final String... args) {
        SSLLog.debug(TAG, message, args);
    }

    @Override
    public boolean isClientMode() {
        return _clientMode;
    }

    @Override
    public void setHandshakeCompletedListener(IHandshakeCompletedListener hcl) {
        _hcl = hcl;
        attachCompletionListener();
    }

    @Override
    public void setSSLListener(ISSLListener l) {
        _worker.setSSLListener(l);
    }

    @Override
    public void setCloseListener(ISessionClosedListener l) {
        _worker.setSessionClosedListener(l);
    }

    @Override
    public void beginHandshake() throws SSLException {
        _handshaker.begin();
    }

    @Override
    public boolean isHandshakeCompleted() {
        return (_handshaker == null) || _handshaker.isFinished();
    }

    @Override
    public void encrypt(ByteBuffer plainData) throws SSLException {
        _worker.wrap(plainData);
    }

    @Override
    public void decrypt(ByteBuffer encryptedData) throws SSLException {
        SSLEngineResult result = _worker.unwrap(encryptedData);
        debug("decrypt: unwrap result=" + result);
        _handshaker.handleUnwrapResult(result);
    }

    @Override
    public void close() {
        /* Called if we want to properly close SSL */
        _worker.close(true);
    }

    @Override
    public boolean isCloseCompleted() {
    /* Host application should only close underlying transport after
     close_notify packet generated by wrap has been sent to peer. Use this
     method to check if the packet has been generated
     */
        return _worker.isCloseCompleted();
    }

    @Override
    public void terminate() {
        /* Called if peer closed connection unexpectedly */
        _worker.close(false);
    }

    /* Privates */
    private void attachCompletionListener() {
        _handshaker.addCompletedListener(new IHandshakeCompletedListener() {
            @Override
            public void onComplete() {
                //_handshaker = null;
                if (_hcl != null) {
                    _hcl.onComplete();
                    _hcl = null;
                }
            }
        });
    }

    private SSLEngine makeSSLEngine(SSLContext context, boolean client, boolean clientAuthRequired) {
        SSLEngine engine = context.createSSLEngine();
        engine.setUseClientMode(client);
        engine.setNeedClientAuth(clientAuthRequired);
        return engine;
    }

}
