/*******************************************************************************
 *Copyright (c) 2009 Eucalyptus Systems, Inc.
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, only version 3 of the License.
 * 
 * 
 * This file is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * for more details.
 * 
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 * 
 * Please contact Eucalyptus Systems, Inc., 130 Castilian
 * Dr., Goleta, CA 93101 USA or visit <http://www.eucalyptus.com/licenses/>
 * if you need additional information or have any questions.
 * 
 * This file may incorporate work covered under the following copyright and
 * permission notice:
 * 
 * Software License Agreement (BSD License)
 * 
 * Copyright (c) 2008, Regents of the University of California
 * All rights reserved.
 * 
 * Redistribution and use of this software in source and binary forms, with
 * or without modification, are permitted provided that the following
 * conditions are met:
 * 
 * Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 * 
 * Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS
 * IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 * PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER
 * OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE. USERS OF
 * THIS SOFTWARE ACKNOWLEDGE THE POSSIBLE PRESENCE OF OTHER OPEN SOURCE
 * LICENSED MATERIAL, COPYRIGHTED MATERIAL OR PATENTED MATERIAL IN THIS
 * SOFTWARE, AND IF ANY SUCH MATERIAL IS DISCOVERED THE PARTY DISCOVERING
 * IT MAY INFORM DR. RICH WOLSKI AT THE UNIVERSITY OF CALIFORNIA, SANTA
 * BARBARA WHO WILL THEN ASCERTAIN THE MOST APPROPRIATE REMEDY, WHICH IN
 * THE REGENTS’ DISCRETION MAY INCLUDE, WITHOUT LIMITATION, REPLACEMENT
 * OF THE CODE SO IDENTIFIED, LICENSING OF THE CODE SO IDENTIFIED, OR
 * WITHDRAWAL OF THE CODE CAPABILITY TO THE EXTENT NEEDED TO COMPLY WITH
 * ANY SUCH LICENSES OR RIGHTS.
 *******************************************************************************/
/*
 * Author: chris grzegorczyk <grze@eucalyptus.com>
 */
package com.eucalyptus.ws.handlers;

import java.io.IOException;

import org.apache.log4j.Logger;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelDownstreamHandler;
import org.jboss.netty.channel.ChannelEvent;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelLocal;
import org.jboss.netty.channel.ChannelPipelineCoverage;
import org.jboss.netty.channel.ChannelUpstreamHandler;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.DownstreamMessageEvent;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelHandler;
import org.jboss.netty.handler.codec.frame.TooLongFrameException;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.jboss.netty.handler.codec.http.HttpVersion;
import org.mule.DefaultMuleMessage;
import org.mule.api.MuleMessage;

import com.eucalyptus.auth.User;
import com.eucalyptus.bootstrap.Component;
import com.eucalyptus.util.EucalyptusCloudException;
import com.eucalyptus.util.EucalyptusProperties;
import com.eucalyptus.ws.MappingHttpMessage;
import com.eucalyptus.ws.MappingHttpRequest;
import com.eucalyptus.ws.MappingHttpResponse;
import com.eucalyptus.ws.client.NioMessageReceiver;
import com.eucalyptus.ws.util.Messaging;
import com.eucalyptus.ws.util.ReplyQueue;

import edu.ucsb.eucalyptus.constants.EventType;
import edu.ucsb.eucalyptus.constants.IsData;
import edu.ucsb.eucalyptus.msgs.EucalyptusErrorMessageType;
import edu.ucsb.eucalyptus.msgs.EucalyptusMessage;
import edu.ucsb.eucalyptus.msgs.EventRecord;
import edu.ucsb.eucalyptus.msgs.GetObjectResponseType;
import edu.ucsb.eucalyptus.msgs.WalrusDataGetResponseType;

@ChannelPipelineCoverage( "one" )
public class ServiceSinkHandler extends SimpleChannelHandler {
  private static Logger                    LOG              = Logger.getLogger( ServiceSinkHandler.class );
  private long                             QUEUE_TIMEOUT_MS = 2;                                           // TODO:
                                                                                                            // measure
                                                                                                            // me
  private long                             startTime;
  private ChannelLocal<MappingHttpMessage> requestLocal     = new ChannelLocal<MappingHttpMessage>( );

  private NioMessageReceiver               msgReceiver;

  public ServiceSinkHandler( ) {
    super( );
  }

  public ServiceSinkHandler( NioMessageReceiver msgReceiver ) {
    super( );
    this.msgReceiver = msgReceiver;
  }

  @Override
  public void handleUpstream( ChannelHandlerContext ctx, ChannelEvent e ) throws Exception {
    LOG.debug( this.getClass( ).getSimpleName( ) + "[incoming]: " + e );
    if ( e instanceof ExceptionEvent ) {
      this.exceptionCaught( ctx, ( ExceptionEvent ) e );
    } else if ( e instanceof MessageEvent ) {
      this.startTime = System.currentTimeMillis( );
      final MessageEvent event = ( MessageEvent ) e;
      if ( event.getMessage( ) instanceof MappingHttpMessage ) {
        MappingHttpMessage request = ( MappingHttpMessage ) event.getMessage( );
        User user = request.getUser( );
        requestLocal.set( ctx.getChannel( ), request );
        EucalyptusMessage msg = ( EucalyptusMessage ) request.getMessage( );
        if ( user != null && msgReceiver == null ) {
          msg.setUserId( user.getUserName( ) );
          msg.setEffectiveUserId( user.getIsAdministrator( ) ? Component.eucalyptus.name( ) : user.getUserName( ) );
        }
        LOG.info( EventRecord.create( this.getClass( ).getSimpleName( ), msg.getUserId( ), msg.getCorrelationId( ), EventType.MSG_RECEIVED, msg.getClass( ).getSimpleName( ) ) );
        ReplyQueue.addReplyListener( msg.getCorrelationId( ), ctx );
        if ( this.msgReceiver == null ) {
          Messaging.dispatch( "vm://RequestQueue", msg );
        } else if ( user == null || ( user != null && user.getIsAdministrator( ) ) ) {
          MuleMessage reply = this.msgReceiver.routeMessage( new DefaultMuleMessage( msg ) );
          ctx.getChannel( ).write( reply.getPayload( ) );
        } else {
          ctx.getChannel( ).write( new MappingHttpResponse( request.getProtocolVersion( ), HttpResponseStatus.FORBIDDEN ) );
        }
      }
    }
  }

  @SuppressWarnings( "unchecked" )
  @Override
  public void handleDownstream( final ChannelHandlerContext ctx, final ChannelEvent e ) throws Exception {
    if ( e instanceof MessageEvent ) {
      MessageEvent msge = ( MessageEvent ) e;
      if ( msge.getMessage( ) instanceof IsData ) {// Pass through for chunked messaging
        ctx.sendDownstream( msge );
      } else if ( msge.getMessage( ) instanceof EucalyptusMessage ) {// Handle
                                                                     // single
                                                                     // request-response
                                                                     // MEP
        MappingHttpMessage request = requestLocal.get( ctx.getChannel( ) );
        EucalyptusMessage reply = ( EucalyptusMessage ) ( ( MessageEvent ) e ).getMessage( );
        if ( reply == null ) {// TODO: fix this error reporting
          LOG.warn( "Received a null response for request: " + request.getMessageString( ) );
          reply = new EucalyptusErrorMessageType( this.getClass( ).getSimpleName( ), ( EucalyptusMessage ) request.getMessage( ), "Received a NULL reply" );
        }
        LOG.info( EventRecord.create( this.getClass( ).getSimpleName( ), reply.getUserId( ), reply.getCorrelationId( ), EventType.MSG_SERVICED, ( System.currentTimeMillis( ) - startTime ) ) );
        if ( reply instanceof WalrusDataGetResponseType ) {
          if ( reply instanceof GetObjectResponseType ) {
            GetObjectResponseType getObjectResponse = ( GetObjectResponseType ) reply;
            if ( getObjectResponse.getBase64Data( ) == null ) return;
          } else {
            return;
          }
        }
        MappingHttpResponse response = new MappingHttpResponse( request.getProtocolVersion( ) );
        DownstreamMessageEvent newEvent = new DownstreamMessageEvent( ctx.getChannel( ), e.getFuture( ), response, null );
        response.setMessage( reply );
        ctx.sendDownstream( newEvent );
      } else {
        ctx.sendDownstream( e );
      }
    } else {
      ctx.sendDownstream( e );
    }
  }

  @Override
  public void exceptionCaught( ChannelHandlerContext ctx, ExceptionEvent e ) {
    Channels.fireExceptionCaught( ctx.getChannel( ), e.getCause( ) );
  }
//    LOG.fatal( e.getCause( ), e.getCause( ) );
//    if ( e.getCause( ) instanceof IOException ) {
//      ctx.getChannel( ).close( );
//      return;
//    } else {
//      HttpResponse error = new DefaultHttpResponse( HttpVersion.HTTP_1_1, HttpResponseStatus.INTERNAL_SERVER_ERROR );
//      Throwable t = e.getCause( );
//      String errMsg = "Error";
//      if ( t != null && t.getMessage( ) != null ) {
//        errMsg = t.getMessage( );
//      } else if ( t != null ) {
//        errMsg = t.toString( );
//      }
//      Channels.fireExceptionCaught( ctx.getChannel( ), new EucalyptusCloudException( errMsg, e.getCause( ) ) );
//      error.setContent( ChannelBuffers.copiedBuffer( errMsg.getBytes( ) ) );
//      DownstreamMessageEvent newEvent = new DownstreamMessageEvent( ctx.getChannel( ), ctx.getChannel( ).getCloseFuture( ), error, null );
//      ctx.sendDownstream( newEvent );
//      newEvent.getFuture( ).addListener( ChannelFutureListener.CLOSE );
//    }
//  }
}
