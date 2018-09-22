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
package com.cloud.hypervisor.bhyve.resource;

import com.cloud.utils.script.OutputInterpreter;
import com.cloud.utils.script.Script;
import org.apache.log4j.Logger;
import org.joda.time.Duration;

import java.util.concurrent.Callable;

public class BhyveHAVMActivityChecker extends BhyveHABase implements Callable<Boolean> {
    private static final Logger LOG = Logger.getLogger(BhyveHAVMActivityChecker.class);

    final private NfsStoragePool nfsStoragePool;
    final private String hostIP;
    final private String volumeUuidList;
    final private String vmActivityCheckPath;
    final private Duration activityScriptTimeout = Duration.standardSeconds(3600L);
    final private long suspectTimeInSeconds;

    public BhyveHAVMActivityChecker(final NfsStoragePool pool, final String host, final String volumeUUIDListString, String vmActivityCheckPath, final long suspectTime) {
        this.nfsStoragePool = pool;
        this.hostIP = host;
        this.volumeUuidList = volumeUUIDListString;
        this.vmActivityCheckPath = vmActivityCheckPath;
        this.suspectTimeInSeconds = suspectTime;
    }

    @Override
    public Boolean checkingHB() {
        Script cmd = new Script(vmActivityCheckPath, activityScriptTimeout.getStandardSeconds(), LOG);
        cmd.add("-i", nfsStoragePool._poolIp);
        cmd.add("-p", nfsStoragePool._poolMountSourcePath);
        cmd.add("-m", nfsStoragePool._mountDestPath);
        cmd.add("-h", hostIP);
        cmd.add("-u", volumeUuidList);
        cmd.add("-t", String.valueOf(String.valueOf(System.currentTimeMillis() / 1000)));
        cmd.add("-d", String.valueOf(suspectTimeInSeconds));
        OutputInterpreter.OneLineParser parser = new OutputInterpreter.OneLineParser();
        String result = cmd.execute(parser);
        LOG.debug("BhyveHAVMActivityChecker pool: " + nfsStoragePool._poolIp);
        LOG.debug("BhyveHAVMActivityChecker result: " + result);
        LOG.debug("BhyveHAVMActivityChecker parser: " + parser.getLine());
        if (result == null && parser.getLine().contains("DEAD")) {
            return false;
        } else {
            return true;
        }
    }

    @Override
    public Boolean call() throws Exception {
        return checkingHB();
    }
}