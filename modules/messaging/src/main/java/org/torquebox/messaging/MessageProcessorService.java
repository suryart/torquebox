/*
 * Copyright 2008-2011 Red Hat, Inc, and individual contributors.
 * 
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 * 
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.torquebox.messaging;

import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageListener;
import javax.jms.Topic;
import javax.jms.XASession;
import javax.transaction.TransactionManager;

import org.jboss.logging.Logger;
import org.jboss.msc.service.Service;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StartException;
import org.jboss.msc.service.StopContext;
import org.jruby.Ruby;
import org.jruby.javasupport.JavaEmbedUtils;
import org.jruby.runtime.builtin.IRubyObject;
import org.torquebox.messaging.component.MessageProcessorComponent;

public class MessageProcessorService implements Service<Void>, MessageListener {

    MessageProcessorService(MessageProcessorGroup group) {
        this.group = group;
    }

    @Override
    public void start(final StartContext context) throws StartException {

        context.asynchronous();
        context.execute( new Runnable() {

            @Override
            public void run() {
                try {
                    session = group.getConnection().createXASession();
                    tm = group.getTransactionManager();
                    Destination destination = group.getDestination();
                    if (group.isDurable() && destination instanceof Topic) {
                        consumer = session.createDurableSubscriber( (Topic) destination, group.getName(), group.getMessageSelector(), false );
                    } else {
                        if (group.isDurable() && !(destination instanceof Topic)) {
                            log.warn( "Durable set for processor " + group.getName() + ", but " + destination + " is not a topic - ignoring." );
                        }
                        consumer = session.createConsumer( destination, group.getMessageSelector() );
                    }
                    consumer.setMessageListener( MessageProcessorService.this );

                    context.complete();
                } catch (JMSException e) {
                    context.failed( new StartException( e ) );
                }
            }
        } );

    }

    @Override
    public void onMessage(Message message) {
        if ( this.consumer == null ) {
            return;
        }
        Ruby ruby = null;
        try {
            tm.begin();
            tm.getTransaction().enlistResource( this.session.getXAResource() );
            ruby = group.getRubyRuntimePool().borrowRuntime();
            
            if ( this.consumer == null ) {
                // while waiting to obtain a Ruby, we got closed.
                // we can't rollback, because the connection is closed.
                return;
            }
            
            MessageProcessorComponent component = (MessageProcessorComponent) group.getComponentResolver().resolve( ruby );
            putSessionInThreadLocal( ruby );
            component.process( message );
            tm.commit();
        } catch (Exception e) {
            log.error( "Unexpected error in " + group.getName(), e );
            try { tm.rollback(); } catch (Exception ignored) {}
        } finally {
            try { tm.suspend(); } catch (Exception ignored) {}
            if (ruby != null) {
                try {
                    removeSessionFromThreadLocal( ruby );
                    group.getRubyRuntimePool().returnRuntime( ruby );
                } catch (Throwable e) {
                    log.warn( "Possible memory leak?", e );
                }
            }
        }

    }

    @Override
    public void stop(StopContext context) {
        try {
            this.consumer.close();
            this.consumer = null;
        } catch (JMSException e) {
            log.error( "Error closing consumer connection", e );
        }
        try {
            this.session.close();
        } catch (JMSException e) {
            log.error( "Error closing consumer session", e );
        }
    }

    @Override
    public Void getValue() throws IllegalStateException, IllegalArgumentException {
        return null;
    }

    protected void putSessionInThreadLocal(Ruby ruby) {
        IRubyObject thread = ruby.evalScriptlet( "Thread.current" );
        JavaEmbedUtils.invokeMethod( ruby, thread, "[]=", new Object[] { "session", session }, Object.class );
    }

    protected void removeSessionFromThreadLocal(Ruby ruby) {
        IRubyObject thread = ruby.evalScriptlet( "Thread.current" );
        JavaEmbedUtils.invokeMethod( ruby, thread, "[]=", new Object[] { "session", null }, Object.class );
    }

    // -------

    private static final Logger log = Logger.getLogger( "org.torquebox.message" );

    private MessageProcessorGroup group;
    private XASession session;
    private MessageConsumer consumer;
    private TransactionManager tm;
}
