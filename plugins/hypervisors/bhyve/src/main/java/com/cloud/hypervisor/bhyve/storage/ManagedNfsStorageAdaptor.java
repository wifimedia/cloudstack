// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.hypervisor.bhyve.storage;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.utils.qemu.QemuImg.PhysicalDiskFormat;
import org.apache.log4j.Logger;
import org.libvirt.Connect;
import org.libvirt.LibvirtException;
import org.libvirt.StoragePool;
import org.libvirt.StoragePoolInfo;
import org.libvirt.StorageVol;

import com.cloud.agent.api.to.DiskTO;
import com.cloud.hypervisor.bhyve.resource.LibvirtConnection;
import com.cloud.hypervisor.bhyve.resource.LibvirtStoragePoolDef;
import com.cloud.hypervisor.bhyve.resource.LibvirtStoragePoolDef.PoolType;
import com.cloud.hypervisor.bhyve.resource.LibvirtStorageVolumeDef;
import com.cloud.hypervisor.bhyve.resource.LibvirtStorageVolumeXMLParser;
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.storage.Storage.ProvisioningType;
import com.cloud.storage.Storage.StoragePoolType;
import com.cloud.storage.StorageLayer;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.script.Script;

public class ManagedNfsStorageAdaptor implements StorageAdaptor {
    private static final Logger s_logger = Logger.getLogger(ManagedNfsStorageAdaptor.class);
    private String _mountPoint = "/mnt";
    private StorageLayer _storageLayer;

    private static final Map<String, BhyveStoragePool> MapStorageUuidToStoragePool = new HashMap<String, BhyveStoragePool>();

    public ManagedNfsStorageAdaptor(StorageLayer storagelayer) {
        _storageLayer = storagelayer;
    }

    @Override
    public BhyveStoragePool createStoragePool(String uuid, String host, int port, String path, String userInfo, StoragePoolType storagePoolType) {

        LibvirtStoragePool storagePool = new LibvirtStoragePool(uuid, path, StoragePoolType.ManagedNFS, this, null);
        storagePool.setSourceHost(host);
        storagePool.setSourcePort(port);
        MapStorageUuidToStoragePool.put(uuid, storagePool);

        return storagePool;
    }

    @Override
    public BhyveStoragePool getStoragePool(String uuid) {
        return getStoragePool(uuid, false);
    }

    @Override
    public BhyveStoragePool getStoragePool(String uuid, boolean refreshInfo) {
        return MapStorageUuidToStoragePool.get(uuid);
    }

    @Override
    public boolean deleteStoragePool(String uuid) {
        return MapStorageUuidToStoragePool.remove(uuid) != null;
    }

    @Override
    public boolean deleteStoragePool(BhyveStoragePool pool) {
        return deleteStoragePool(pool.getUuid());
    }

    public BhyvePhysicalDisk createPhysicalDisk(String volumeUuid, BhyveStoragePool pool, PhysicalDiskFormat format, long size) {
        throw new UnsupportedOperationException("Creating a physical disk is not supported.");
    }

    /*
     * creates a nfs storage pool using libvirt
     */
    @Override
    public boolean connectPhysicalDisk(String volumeUuid, BhyveStoragePool pool, Map<String, String> details) {

        StoragePool sp = null;
        Connect conn = null;
        String targetPath = null;
        LibvirtStoragePoolDef spd = null;
        try {
            conn = LibvirtConnection.getConnection();
            if (conn == null) {
                throw new CloudRuntimeException("Failed to create Libvrt Connection");
            }

            targetPath = "/mnt" + volumeUuid;
            spd = new LibvirtStoragePoolDef(PoolType.NETFS, volumeUuid, details.get(DiskTO.UUID), pool.getSourceHost(), details.get(DiskTO.MOUNT_POINT), targetPath);
            _storageLayer.mkdir(targetPath);

            s_logger.debug(spd.toString());
            sp = conn.storagePoolCreateXML(spd.toString(), 0);

            if (sp == null) {
                throw new CloudRuntimeException("Failed to create storage pool:" + volumeUuid);
            }

            try {
                if (sp.isActive() == 0) {
                    // s_logger.debug("attempting to activate pool " + name);
                    sp.create(0);
                }
                // now add the storage pool
                LibvirtStoragePool storagePool = (LibvirtStoragePool) getStoragePool(pool.getUuid());
                storagePool.setPool(sp);

                return true;
            } catch (LibvirtException e) {
                String error = e.toString();
                if (error.contains("Storage source conflict")) {
                    throw new CloudRuntimeException("A pool matching this location already exists in libvirt, " + " but has a different UUID/Name. Cannot create new pool without first "
                            + " removing it. Check for inactive pools via 'virsh pool-list --all'. " + error);
                } else {
                    throw new CloudRuntimeException(error);
                }
            }
        } catch (LibvirtException e) {
            s_logger.error(e.toString());
            // if error is that pool is mounted, try to handle it
            if (e.toString().contains("already mounted")) {
                s_logger.error("Attempting to unmount old mount libvirt is unaware of at " + targetPath);
                String result = Script.runSimpleBashScript("umount -l " + targetPath);
                if (result == null) {
                    s_logger.error("Succeeded in unmounting " + targetPath);
                    try {
                        conn.storagePoolCreateXML(spd.toString(), 0);
                        s_logger.error("Succeeded in redefining storage");
                        return true;
                    } catch (LibvirtException l) {
                        s_logger.error("Target was already mounted, unmounted it but failed to redefine storage:" + l);
                    }
                } else {
                    s_logger.error("Failed in unmounting and redefining storage");
                }
            } else {
                s_logger.error("Internal error occurred when attempting to mount:" + e.getMessage());
                // stacktrace for agent.log
                e.printStackTrace();
                throw new CloudRuntimeException(e.toString());
            }
            return false;
        }

    }

    /*
     * creates a disk based on the created nfs storage pool using libvirt
     */
    @Override
    public BhyvePhysicalDisk getPhysicalDisk(String volumeUuid, BhyveStoragePool pool) {
        // now create the volume upon the given storage pool in kvm
        Connect conn;
        StoragePool virtPool = null;
        try {
            conn = LibvirtConnection.getConnection();
            virtPool = conn.storagePoolLookupByName("/" + volumeUuid);
        } catch (LibvirtException e1) {
            throw new CloudRuntimeException(e1.toString());
        }

        LibvirtStorageVolumeDef.VolumeFormat libvirtformat = null;
        long volCapacity = 0;
        // check whether the volume is present on the given pool
        StorageVol vol = getVolume(virtPool, volumeUuid);
        try {
            if (vol == null) {

                libvirtformat = LibvirtStorageVolumeDef.VolumeFormat.QCOW2;

                StoragePoolInfo poolinfo = virtPool.getInfo();
                volCapacity = poolinfo.available;

                LibvirtStorageVolumeDef volDef = new LibvirtStorageVolumeDef(volumeUuid, volCapacity, libvirtformat, null, null);
                s_logger.debug(volDef.toString());

                vol = virtPool.storageVolCreateXML(volDef.toString(), 0);

            }
            BhyvePhysicalDisk disk = new BhyvePhysicalDisk(vol.getPath(), volumeUuid, pool);
            disk.setFormat(PhysicalDiskFormat.QCOW2);
            disk.setSize(vol.getInfo().allocation);
            disk.setVirtualSize(vol.getInfo().capacity);
            return disk;

        } catch (LibvirtException e) {
            throw new CloudRuntimeException(e.toString());
        }

    }

    public LibvirtStorageVolumeDef getStorageVolumeDef(Connect conn, StorageVol vol) throws LibvirtException {
        String volDefXML = vol.getXMLDesc(0);
        LibvirtStorageVolumeXMLParser parser = new LibvirtStorageVolumeXMLParser();
        return parser.parseStorageVolumeXML(volDefXML);
    }

    public StorageVol getVolume(StoragePool pool, String volName) {
        StorageVol vol = null;

        try {
            vol = pool.storageVolLookupByName(volName);
        } catch (LibvirtException e) {
            s_logger.debug("Can't find volume: " + e.toString());
        }
        if (vol == null) {
            try {
                refreshPool(pool);
            } catch (LibvirtException e) {
                s_logger.debug("failed to refresh pool: " + e.toString());
            }
            s_logger.debug("no volume is present on the pool, creating a new one");
        }
        return vol;
    }

    private void refreshPool(StoragePool pool) throws LibvirtException {
        pool.refresh(0);
        return;
    }

    /*
     * disconnect the disk by destroying the sp pointer
     */
    public boolean disconnectPhysicalDisk(BhyveStoragePool pool, String mountpoint) throws LibvirtException {

        LibvirtStoragePool libvirtPool = (LibvirtStoragePool) pool;
        StoragePool sp = libvirtPool.getPool();
        // destroy the pool
        sp.destroy();

        return true;
    }

    @Override
    public boolean disconnectPhysicalDisk(String volumeUuid, BhyveStoragePool pool) {
        try {
            return disconnectPhysicalDisk(pool, volumeUuid);
        } catch (LibvirtException e) {
            throw new CloudRuntimeException(e.getMessage());
        }
    }

    @Override
    public boolean disconnectPhysicalDisk(Map<String, String> volumeToDisconnect) {
        return false;
    }

    @Override
    public boolean disconnectPhysicalDiskByPath(String localPath) {
        return false;
    }

    public boolean deletePhysicalDisk(String volumeUuid, BhyveStoragePool pool) {
        throw new UnsupportedOperationException("Deleting a physical disk is not supported.");
    }

    @Override
    public List<BhyvePhysicalDisk> listPhysicalDisks(String storagePoolUuid, BhyveStoragePool pool) {
        throw new UnsupportedOperationException("Listing disks is not supported for this configuration.");
    }

    public BhyvePhysicalDisk createDiskFromTemplate(BhyvePhysicalDisk template, String name, PhysicalDiskFormat format, long size, BhyveStoragePool destPool, int timeout) {
        throw new UnsupportedOperationException("Creating a disk from a template is not yet supported for this configuration.");
    }

    @Override
    public BhyvePhysicalDisk createTemplateFromDisk(BhyvePhysicalDisk disk, String name, PhysicalDiskFormat format, long size, BhyveStoragePool destPool) {
        throw new UnsupportedOperationException("Creating a template from a disk is not yet supported for this configuration.");
    }

    @Override
    public BhyvePhysicalDisk copyPhysicalDisk(BhyvePhysicalDisk disk, String name, BhyveStoragePool destPool, int timeout) {
        throw new UnsupportedOperationException("Copying a disk is not supported in this configuration.");
    }

    @Override
    public BhyvePhysicalDisk createDiskFromSnapshot(BhyvePhysicalDisk snapshot, String snapshotName, String name, BhyveStoragePool destPool) {
        throw new UnsupportedOperationException("Creating a disk from a snapshot is not supported in this configuration.");
    }

    @Override
    public boolean refresh(BhyveStoragePool pool) {
        return true;
    }

    @Override
    public boolean createFolder(String uuid, String path) {
        String mountPoint = _mountPoint + File.separator + uuid;
        File f = new File(mountPoint + File.separator + path);
        if (!f.exists()) {
            return f.mkdirs();
        }
        return true;
    }

    @Override
    public BhyvePhysicalDisk createPhysicalDisk(String name, BhyveStoragePool pool, PhysicalDiskFormat format, ProvisioningType provisioningType, long size) {
        return null;
    }

    @Override
    public boolean deletePhysicalDisk(String uuid, BhyveStoragePool pool, ImageFormat format) {
        return false;
    }

    @Override
    public BhyvePhysicalDisk createDiskFromTemplate(BhyvePhysicalDisk template, String name, PhysicalDiskFormat format, ProvisioningType provisioningType, long size, BhyveStoragePool destPool, int timeout) {
        return null;
    }
}