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

package com.eucalyptus.cluster;

import com.eucalyptus.bootstrap.Component;
import com.eucalyptus.cluster.callback.BundleCallback;
import com.eucalyptus.cluster.callback.QueuedEventCallback;
import com.eucalyptus.util.HasName;
import com.eucalyptus.util.LogUtil;
import com.eucalyptus.vm.SystemState;
import com.eucalyptus.vm.VmState;
import com.eucalyptus.records.EventType;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import edu.ucsb.eucalyptus.cloud.Network;
import edu.ucsb.eucalyptus.cloud.VmImageInfo;
import edu.ucsb.eucalyptus.cloud.VmKeyInfo;
import edu.ucsb.eucalyptus.msgs.AttachedVolume;
import edu.ucsb.eucalyptus.msgs.BundleTask;
import com.eucalyptus.records.EventRecord;
import edu.ucsb.eucalyptus.msgs.NetworkConfigType;
import edu.ucsb.eucalyptus.msgs.RunningInstancesItemType;
import edu.ucsb.eucalyptus.msgs.VmTypeInfo;
import org.apache.commons.lang.time.StopWatch;
import org.apache.log4j.Logger;
import org.jboss.util.collection.ConcurrentSet;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicMarkableReference;

public class VmInstance implements HasName {
  private static Logger LOG          = Logger.getLogger( VmInstance.class );
  public static String  DEFAULT_IP   = "0.0.0.0";
  public static String  DEFAULT_TYPE = "m1.small";
  
  public enum BundleState {
    none( "none" ), pending( null ), storing( "bundling" ), canceling( null ), complete( "succeeded" ), failed( "failed" );
    private String mappedState;
    
    BundleState( String mappedState ) {
      this.mappedState = mappedState;
    }
    
    public String getMappedState( ) {
      return this.mappedState;
    }
  }
  
  private final String                                reservationId;
  private final int                                   launchIndex;
  private final String                                instanceId;
  private final String                                ownerId;
  private final String                                placement;
  private final String                                userData;
  private final List<Network>                         networks      = Lists.newArrayList( );
  private final NetworkConfigType                     networkConfig = new NetworkConfigType( );
  private String                                      platform;
  private VmImageInfo                                 imageInfo;
  private VmKeyInfo                                   keyInfo;
  private VmTypeInfo                                  vmTypeInfo;
  
  private final AtomicMarkableReference<VmState>      state         = new AtomicMarkableReference<VmState>( VmState.PENDING, false );
  private final AtomicMarkableReference<BundleTask>   bundleTask    = new AtomicMarkableReference<BundleTask>( null, false );
  private final ConcurrentSkipListSet<AttachedVolume> volumes       = new ConcurrentSkipListSet<AttachedVolume>( );
  private final StopWatch                             stopWatch     = new StopWatch( );
  
  private Date                                        launchTime    = new Date( );
  private String                                      serviceTag;
  private String                                      reason;
  private StringBuffer                                consoleOutput = new StringBuffer( );
  private String                                      passwordData;
  private Boolean                                     privateNetwork;
  
  public VmInstance( final String reservationId, final int launchIndex, final String instanceId, final String ownerId, final String placement,
                     final String userData, final VmImageInfo imageInfo, final VmKeyInfo keyInfo, final VmTypeInfo vmTypeInfo, final List<Network> networks,
                     final String networkIndex ) {
    this.reservationId = reservationId;
    this.launchIndex = launchIndex;
    this.instanceId = instanceId;
    this.ownerId = ownerId;
    this.placement = placement;
    this.userData = userData;
    this.imageInfo = imageInfo;
    this.platform = this.imageInfo.getPlatform( );
    this.keyInfo = keyInfo;
    this.vmTypeInfo = vmTypeInfo;
    this.networks.addAll( networks );
    this.networkConfig.setMacAddress( "d0:0d:" + VmInstances.asMacAddress( this.instanceId ) );
    this.networkConfig.setIpAddress( DEFAULT_IP );
    this.networkConfig.setIgnoredPublicIp( DEFAULT_IP );
    this.networkConfig.setNetworkIndex( Integer.parseInt( networkIndex ) );
    this.stopWatch.start( );
  }
  
  public void updateNetworkIndex( Integer newIndex ) {
    if ( this.getNetworkConfig( ).getNetworkIndex( ) > 0 && newIndex > 0
         && ( VmState.RUNNING.equals( this.getState( ) ) || VmState.PENDING.equals( this.getState( ) ) ) ) {
      this.getNetworkConfig( ).setNetworkIndex( newIndex );
    }
  }
  
  public void updateAddresses( String privateAddr, String publicAddr ) {
    if ( !VmInstance.DEFAULT_IP.equals( privateAddr ) && !"".equals( privateAddr ) && privateAddr != null ) {
      this.getNetworkConfig( ).setIpAddress( privateAddr );
    }
    if ( VmInstance.DEFAULT_IP.equals( this.getNetworkConfig( ).getIgnoredPublicIp( ) ) && !VmInstance.DEFAULT_IP.equals( publicAddr )
         && !"".equals( publicAddr ) && publicAddr != null ) {
      this.getNetworkConfig( ).setIgnoredPublicIp( publicAddr );
    }
    String dnsDomain = "dns-disabled";
    try {
      dnsDomain = edu.ucsb.eucalyptus.cloud.entities.SystemConfiguration.getSystemConfiguration( ).getDnsDomain( );
    } catch ( Exception e ) {}
    this.getNetworkConfig( ).updateDns( dnsDomain );
  }
  
  public boolean clearPending( ) {
    if ( this.state.isMarked( ) && this.getState( ).ordinal( ) > VmState.RUNNING.ordinal( ) ) {
      this.state.set( this.getState( ), false );
      VmInstances.cleanUp( this );
      return true;
    } else {
      this.state.set( this.getState( ), false );
      return false;
    }
  }
  
  public VmState getState( ) {
    return this.state.getReference( );
  }
  
  public void setState( final VmState state ) {
    this.setState( state, "Operating normally." );
  }
  
  private int stateCounter = 0;
  public void setState( final VmState newState, String reason ) {
    this.resetStopWatch( );
    VmState oldState = this.state.getReference( );
    if( VmState.SHUTTING_DOWN.equals( newState ) && VmState.SHUTTING_DOWN.equals( oldState ) && SystemState.INSTANCE_TERMINATED.equals( reason ) ) {
      VmInstances.cleanUp( this );
    } else if( VmState.TERMINATED.equals( newState ) && VmState.TERMINATED.equals( oldState ) ) {
      VmInstances.getInstance( ).deregister( this.getName( ) );
    } else if ( !this.getState( ).equals( newState ) ) {
      LOG.info( String.format( "%s state change: %s -> %s", this.getInstanceId( ), this.getState( ), newState ) );
      this.reason = reason;
      if ( this.state.isMarked( ) && VmState.PENDING.equals( this.getState( ) ) ) {
        if ( VmState.SHUTTING_DOWN.equals( newState ) ) {
          this.state.set( newState, true );
        } else {
          this.state.set( newState, false );
        }
      } else if ( this.state.isMarked( ) && VmState.SHUTTING_DOWN.equals( this.getState( ) ) ) {
        LOG.debug( "Ignoring events for state transition because the instance is marked as pending: " + oldState + " to " + this.getState( ) );
      } else if ( !this.state.isMarked( ) ) {
        if ( oldState.ordinal( ) <= VmState.RUNNING.ordinal( ) && newState.ordinal( ) > VmState.RUNNING.ordinal( ) ) {
          this.state.set( newState, false );
          VmInstances.cleanUp( this );
        } else if ( VmState.PENDING.equals( oldState ) && VmState.RUNNING.equals( newState ) ) {
          this.state.set( newState, false );
        } else if ( VmState.TERMINATED.equals( newState ) && oldState.ordinal( ) <= VmState.RUNNING.ordinal( )  ) {
          this.state.set( newState, false );
          VmInstances.getInstance( ).disable( this.getName( ) );
          VmInstances.cleanUp( this );
        } else if ( VmState.TERMINATED.equals( newState ) && oldState.ordinal( ) > VmState.RUNNING.ordinal( )  ) {
          this.state.set( newState, false );
          VmInstances.getInstance( ).disable( this.getName( ) );
        } else if ( oldState.ordinal( ) > VmState.RUNNING.ordinal( ) && newState.ordinal( ) <= VmState.RUNNING.ordinal( ) ) {
          this.state.set( oldState, false );
          VmInstances.cleanUp( this );
        } else if( newState.ordinal( ) > oldState.ordinal( ) ) {
          this.state.set( newState, false );
        }
      } else {
        LOG.debug( "Ignoring events for state transition because the instance is marked as pending: " + oldState + " to " + this.getState( ) );
      }
      if ( !this.getState( ).equals( oldState ) ) {
        EventRecord.caller( VmInstance.class, EventType.VM_STATE, this.instanceId, this.ownerId, this.state.getReference( ).name( ), this.launchTime );
      }
    }
  }
  
  public String getByKey( String path ) {
    Map<String, String> m = getMetadataMap( );
    if ( path == null ) path = "";
    LOG.debug( "Servicing metadata request:" + path + " -> " + m.get( path ) );
    if ( m.containsKey( path + "/" ) ) path += "/";
    return m.get( path ).replaceAll( "\n*\\z", "" );
  }
  
  private Map<String, String> getMetadataMap( ) {
    Map<String, String> m = new HashMap<String, String>( );
    m.put( "ami-id", this.getImageInfo( ).getImageId( ) );
    m.put( "product-codes", this.getImageInfo( ).getProductCodes( ).toString( ).replaceAll( "[\\Q[]\\E]", "" ).replaceAll( ", ", "\n" ) );
    m.put( "ami-launch-index", "" + this.getLaunchIndex( ) );
    m.put( "ancestor-ami-ids", this.getImageInfo( ).getAncestorIds( ).toString( ).replaceAll( "[\\Q[]\\E]", "" ).replaceAll( ", ", "\n" ) );
    
    m.put( "ami-manifest-path", this.getImageInfo( ).getImageLocation( ) );
    m.put( "hostname", this.getNetworkConfig( ).getIgnoredPublicIp( ) );
    m.put( "instance-id", this.getInstanceId( ) );
    m.put( "instance-type", this.getVmTypeInfo( ).getName( ) );
    if ( Component.dns.isLocal( ) ) {
      m.put( "local-hostname", this.getNetworkConfig( ).getPrivateDnsName( ) );
    } else {
      m.put( "local-hostname", this.getNetworkConfig( ).getIpAddress( ) );
    }
    m.put( "local-ipv4", this.getNetworkConfig( ).getIpAddress( ) );
    if ( Component.dns.isLocal( ) ) {
      m.put( "public-hostname", this.getNetworkConfig( ).getPublicDnsName( ) );
    } else {
      m.put( "public-hostname", this.getNetworkConfig( ).getIgnoredPublicIp( ) );
    }
    m.put( "public-ipv4", this.getNetworkConfig( ).getIgnoredPublicIp( ) );
    m.put( "reservation-id", this.getReservationId( ) );
    m.put( "kernel-id", this.getImageInfo( ).getKernelId( ) );
    if ( this.getImageInfo( ).getRamdiskId( ) != null ) {
      m.put( "ramdisk-id", this.getImageInfo( ).getRamdiskId( ) );
    }
    m.put( "security-groups", this.getNetworkNames( ).toString( ).replaceAll( "[\\Q[]\\E]", "" ).replaceAll( ", ", "\n" ) );
    
    m.put( "block-device-mapping/", "emi\nephemeral0\nroot\nswap" );
    m.put( "block-device-mapping/emi", "sda1" );
    m.put( "block-device-mapping/ami", "sda1" );
    m.put( "block-device-mapping/ephemeral", "sda2" );
    m.put( "block-device-mapping/swap", "sda3" );
    m.put( "block-device-mapping/root", "/dev/sda1" );
    
    m.put( "public-keys/", "0=" + this.getKeyInfo( ).getName( ) );
    m.put( "public-keys/0", "openssh-key" );
    m.put( "public-keys/0/", "openssh-key" );
    m.put( "public-keys/0/openssh-key", this.getKeyInfo( ).getValue( ) );
    
    m.put( "placement/", "availability-zone" );
    m.put( "placement/availability-zone", this.getPlacement( ) );
    String dir = "";
    for ( String entry : m.keySet( ) ) {
      if ( entry.contains( "/" ) && !entry.endsWith( "/" ) ) continue;
      dir += entry + "\n";
    }
    m.put( "", dir );
    return m;
  }
  
  public int compareTo( final Object o ) {
    VmInstance that = ( VmInstance ) o;
    return this.getName( ).compareTo( that.getName( ) );
  }
  
  public synchronized long resetStopWatch( ) {
    this.stopWatch.stop( );
    long ret = this.stopWatch.getTime( );
    this.stopWatch.reset( );
    this.stopWatch.start( );
    return ret;
  }
  
  public synchronized long getSplitTime( ) {
    this.stopWatch.split( );
    long ret = this.stopWatch.getSplitTime( );
    this.stopWatch.unsplit( );
    return ret;
  }
  
  public Boolean isBundling( ) {
    return this.bundleTask.getReference( ) != null;
  }
  
  public BundleTask resetBundleTask( ) {
    BundleTask oldTask = this.bundleTask.getReference( );
    this.bundleTask.set( null, false );
    EventRecord.here( BundleCallback.class, EventType.BUNDLE_RESET, this.getOwnerId( ), this.getBundleTask( ).getBundleId( ), this.getInstanceId( ) ).info( );
    return oldTask;
  }
  
  private BundleState getBundleTaskState( ) {
    if ( this.bundleTask.getReference( ) != null ) {
      return BundleState.valueOf( this.getBundleTask( ).getState( ) );
    } else {
      return null;
    }
  }
  
  public void setBundleTaskState( String state ) {
    BundleState next = null;
    if ( BundleState.storing.getMappedState( ).equals( state ) ) {
      next = BundleState.storing;
    } else if ( BundleState.complete.getMappedState( ).equals( state ) ) {
      next = BundleState.complete;
    } else if ( BundleState.failed.getMappedState( ).equals( state ) ) {
      next = BundleState.failed;
    } else {
      next = BundleState.none;
    }
    if ( this.bundleTask.getReference( ) != null ) {
      if ( !this.bundleTask.isMarked( ) ) {
        BundleState current = BundleState.valueOf( this.getBundleTask( ).getState( ) );
        if ( BundleState.complete.equals( current ) || BundleState.failed.equals( current ) ) {
          return; //already finished, wait and timeout the state along with the instance.
        } else if ( BundleState.storing.equals( next ) || BundleState.storing.equals( current ) ) {
          this.getBundleTask( ).setState( next.name( ) );
          EventRecord.here( BundleCallback.class, EventType.BUNDLE_TRANSITION, this.getOwnerId( ), this.getBundleTask( ).getBundleId( ), this.getInstanceId( ),
                            this.getBundleTask( ).getState( ) ).info( );
          this.getBundleTask( ).setUpdateTime( new Date( ) );
        } else if ( BundleState.none.equals( next ) && BundleState.failed.equals( current ) ) {
          this.resetBundleTask( );
        }
      } else {
        this.getBundleTask( ).setUpdateTime( new Date( ) );
      }
    }
  }
  
  public Boolean cancelBundleTask( ) {
    if ( this.getBundleTask( ) != null ) {
      this.bundleTask.set( this.getBundleTask( ), true );
      this.getBundleTask( ).setState( BundleState.canceling.name( ) );
      EventRecord.here( BundleCallback.class, EventType.BUNDLE_CANCELING, this.getOwnerId( ), this.getBundleTask( ).getBundleId( ), this.getInstanceId( ),
                        this.getBundleTask( ).getState( ) ).info( );
      return true;
    } else {
      return false;
    }
  }
  
  public Boolean clearPendingBundleTask( ) {
    if ( BundleState.pending.name( ).equals( this.getBundleTask( ).getState( ) )
         && this.bundleTask.compareAndSet( this.getBundleTask( ), this.getBundleTask( ), true, false ) ) {
      this.getBundleTask( ).setState( BundleState.storing.name( ) );
      EventRecord.here( BundleCallback.class, EventType.BUNDLE_STARTING, this.getOwnerId( ), this.getBundleTask( ).getBundleId( ), this.getInstanceId( ),
                        this.getBundleTask( ).getState( ) ).info( );
      return true;
    } else if ( BundleState.canceling.name( ).equals( this.getBundleTask( ).getState( ) )
                && this.bundleTask.compareAndSet( this.getBundleTask( ), this.getBundleTask( ), true, false ) ) {
      EventRecord.here( BundleCallback.class, EventType.BUNDLE_CANCELLED, this.getOwnerId( ), this.getBundleTask( ).getBundleId( ), this.getInstanceId( ),
                        this.getBundleTask( ).getState( ) ).info( );
      this.resetBundleTask( );
      return true;
    } else {
      return false;
    }
  }
  
  public Boolean startBundleTask( BundleTask task ) {
    if ( this.bundleTask.compareAndSet( null, task, false, true ) ) {
      return true;
    } else {
      if ( this.getBundleTask( ) != null && BundleState.failed.equals( BundleState.valueOf( this.getBundleTask( ).getState( ) ) ) ) {
        this.resetBundleTask( );
        this.bundleTask.set( task, true );
        return true;
      } else {
        return false;
      }
    }
  }
  
  public RunningInstancesItemType getAsRunningInstanceItemType( boolean dns ) {
    RunningInstancesItemType runningInstance = new RunningInstancesItemType( );
    
    runningInstance.setAmiLaunchIndex( Integer.toString( this.launchIndex ) );
    if ( this.getBundleTaskState( ) != null && !BundleState.none.equals( this.getBundleTaskState( ) ) ) {
      runningInstance.setStateCode( Integer.toString( VmState.TERMINATED.getCode( ) ) );
      runningInstance.setStateName( VmState.TERMINATED.getName( ) );
    } else {
      runningInstance.setStateCode( Integer.toString( this.state.getReference( ).getCode( ) ) );
      runningInstance.setStateName( this.state.getReference( ).getName( ) );
    }
    runningInstance.setPlatform( this.getPlatform( ) );
    runningInstance.setInstanceId( this.instanceId );
    runningInstance.setImageId( this.imageInfo.getImageId( ) );
    runningInstance.setKernel( this.imageInfo.getKernelId( ) );
    runningInstance.setRamdisk( this.imageInfo.getRamdiskId( ) );
    runningInstance.setProductCodes( this.imageInfo.getProductCodes( ) );
    
    if ( dns ) {
      runningInstance.setDnsName( this.getNetworkConfig( ).getPublicDnsName( ) );
      runningInstance.setPrivateDnsName( this.getNetworkConfig( ).getPrivateDnsName( ) );
    } else {
      runningInstance.setPrivateDnsName( this.getNetworkConfig( ).getIpAddress( ) );
      if ( !VmInstance.DEFAULT_IP.equals( this.getNetworkConfig( ).getIgnoredPublicIp( ) ) ) {
        runningInstance.setDnsName( this.getNetworkConfig( ).getIgnoredPublicIp( ) );
      } else {
        runningInstance.setDnsName( this.getNetworkConfig( ).getIpAddress( ) );
      }
    }
    if ( this.getReason( ) != null || !"".equals( this.getReason( ) ) ) runningInstance.setReason( this.getReason( ) );
    
    if ( this.getKeyInfo( ) != null )
      runningInstance.setKeyName( this.getKeyInfo( ).getName( ) );
    else runningInstance.setKeyName( "" );
    
    runningInstance.setInstanceType( this.getVmTypeInfo( ).getName( ) );
    runningInstance.setPlacement( this.placement );
    
    runningInstance.setLaunchTime( this.launchTime );
    
    return runningInstance;
  }
  
  public BundleTask getBundleTask( ) {
    return this.bundleTask.getReference( );
  }
  
  public void setServiceTag( String serviceTag ) {
    this.serviceTag = serviceTag;
  }
  
  public String getServiceTag( ) {
    return serviceTag;
  }
  
  public boolean hasPublicAddress( ) {
    NetworkConfigType conf = getNetworkConfig( );
    return conf != null && !( DEFAULT_IP.equals( conf.getIgnoredPublicIp( ) ) || conf.getIpAddress( ).equals( conf.getIgnoredPublicIp( ) ) );
  }
  
  public String getName( ) {
    return this.instanceId;
  }
  
  public void setLaunchTime( final Date launchTime ) {
    this.launchTime = launchTime;
  }
  
  public String getReservationId( ) {
    return reservationId;
  }
  
  public String getInstanceId( ) {
    return instanceId;
  }
  
  public String getOwnerId( ) {
    return ownerId;
  }
  
  public int getLaunchIndex( ) {
    return launchIndex;
  }
  
  public String getPlacement( ) {
    return placement;
  }
  
  public Date getLaunchTime( ) {
    return launchTime;
  }
  
  public String getUserData( ) {
    return userData;
  }
  
  public VmKeyInfo getKeyInfo( ) {
    return keyInfo;
  }
  
  public String getReason( ) {
    return reason;
  }
  
  public void setReason( final String reason ) {
    this.reason = reason;
  }
  
  public StringBuffer getConsoleOutput( ) {
    return consoleOutput;
  }
  
  public void setConsoleOutput( final StringBuffer consoleOutput ) {
    this.consoleOutput = consoleOutput;
    if ( this.passwordData == null ) {
      String tempCo = consoleOutput.toString( ).replaceAll( "[\r\n]*", "" );
      if ( tempCo.matches( ".*<Password>[\\w=+/]*</Password>.*" ) ) {
        this.passwordData = tempCo.replaceAll( ".*<Password>", "" ).replaceAll( "</Password>.*", "" );
      }
    }
  }
  
  public void setKeyInfo( final VmKeyInfo keyInfo ) {
    this.keyInfo = keyInfo;
  }
  
  public VmTypeInfo getVmTypeInfo( ) {
    return vmTypeInfo;
  }
  
  public void setVmTypeInfo( final VmTypeInfo vmTypeInfo ) {
    this.vmTypeInfo = vmTypeInfo;
  }
  
  public List<Network> getNetworks( ) {
    return networks;
  }
  
  public List<String> getNetworkNames( ) {
    List<String> nets = new ArrayList<String>( );
    for ( Network net : this.getNetworks( ) )
      nets.add( net.getNetworkName( ) );
    return nets;
  }
  
  public NetworkConfigType getNetworkConfig( ) {
    return networkConfig;
  }
  
  public VmImageInfo getImageInfo( ) {
    return imageInfo;
  }
  
  public void setImageInfo( final VmImageInfo imageInfo ) {
    this.imageInfo = imageInfo;
  }
  
  public void updateVolumeState( final String volumeId, String state ) {
    AttachedVolume v = Iterables.find( this.volumes, new Predicate<AttachedVolume>( ) {
      @Override
      public boolean apply( AttachedVolume arg0 ) {
        return arg0.getVolumeId( ).equals( volumeId );
      }
    } );
    v.setStatus( state );
  }
  
  public NavigableSet<AttachedVolume> getVolumes( ) {
    return this.volumes;
  }
  
  public void setVolumes( final List<AttachedVolume> newVolumes ) {
    for ( AttachedVolume vol : newVolumes ) {
      vol.setInstanceId( this.getInstanceId( ) );
      vol.setStatus( "attached" );
    }
    Set<AttachedVolume> oldVolumes = Sets.newHashSet( this.getVolumes( ) );
    this.volumes.retainAll( volumes );
    this.volumes.addAll( volumes );
    for ( AttachedVolume v : oldVolumes ) {
      if ( "attaching".equals( v.getStatus( ) ) && !this.volumes.contains( v ) ) {
        this.volumes.add( v );
      }
    }
  }
  
  public String getPasswordData( ) {
    return this.passwordData;
  }
  
  public void setPasswordData( String passwordData ) {
    this.passwordData = passwordData;
  }
  
  public String getPlatform( ) {
    return this.platform;
  }
  
  public void setPlatform( String platform ) {
    this.platform = platform;
  }
  
  @Override
  public boolean equals( Object o ) {
    if ( this == o ) return true;
    if ( o == null || getClass( ) != o.getClass( ) ) return false;
    
    VmInstance vmInstance = ( VmInstance ) o;
    
    if ( !instanceId.equals( vmInstance.instanceId ) ) return false;
    
    return true;
  }
  
  @Override
  public int hashCode( ) {
    return instanceId.hashCode( );
  }
  
  @Override
  public String toString( ) {
    return String
                 .format(
                          "VmInstance [imageInfo=%s, instanceId=%s, keyInfo=%s, launchIndex=%s, launchTime=%s, networkConfig=%s, networks=%s, ownerId=%s, placement=%s, privateNetwork=%s, reason=%s, reservationId=%s, state=%s, stopWatch=%s, userData=%s, vmTypeInfo=%s, volumes=%s, bundleTask=%s]",
                          this.imageInfo, this.instanceId, this.keyInfo, this.launchIndex, this.launchTime, this.networkConfig, this.networks, this.ownerId,
                          this.placement, this.privateNetwork, this.reason, this.reservationId, this.state, this.stopWatch, this.userData, this.vmTypeInfo,
                          this.volumes, this.getBundleTask( ) != null ? LogUtil.dumpObject( this.getBundleTask( ) ) : "null" );
  }
  
}
