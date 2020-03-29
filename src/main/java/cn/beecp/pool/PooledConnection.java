/*
 * Copyright Chris2018998
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cn.beecp.pool;

import cn.beecp.BeeDataSourceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;

import static cn.beecp.util.BeecpUtil.oclose;
import static java.lang.System.currentTimeMillis;

/**
 * Pooled Connection
 *
 * @author Chris.Liao
 * @version 1.0
 */
class PooledConnection extends StatementCache{
	volatile int state;
	boolean stmCacheValid;
	BeeDataSourceConfig pConfig;
	Connection rawConn;

	ProxyConnectionBase proxyConn;
	volatile long lastAccessTime;
	boolean commitDirtyInd;
	boolean curAutoCommit;
	private FastConnectionPool pool;
	private static Logger log = LoggerFactory.getLogger(PooledConnection.class);
	
	//changed indicator
	private boolean[] changedInds=new boolean[6]; //0:autoCommit,1:transactionIsolation,2:readOnly,3:catalog,4:schema,5:networkTimeout
	private short changedCount=0;

	public PooledConnection(Connection rawConn,int connState,FastConnectionPool connPool,BeeDataSourceConfig config)throws SQLException{
		super(config.getPreparedStatementCacheSize());
		pool=connPool;
		state=connState;
		this.rawConn=rawConn;

		pConfig=config;
		curAutoCommit=pConfig.isDefaultAutoCommit();
		stmCacheValid = pConfig.getPreparedStatementCacheSize()>0;
		updateAccessTime();
	}
	public String toString() { return rawConn.toString();}
	public boolean equals(Object obj) { return this==obj;}

	void closeRawConn() {//called by pool
		if(proxyConn!=null){
			proxyConn.setAsClosed();
			proxyConn=null;
		}

		resetRawConnOnReturn();
		if(stmCacheValid)
			this.clearStatement();
		oclose(rawConn);
	}

	//***************called by connection proxy ********//
	void returnToPoolBySelf(){
		proxyConn=null;
		if(resetRawConnOnReturn())
			pool.recycle(this);
		else
		    pool.abandonOnReturn(this);
	}
	void setCurAutoCommit(boolean curAutoCommit) {
		this.curAutoCommit = curAutoCommit;
	}
	void updateAccessTime() {
		lastAccessTime = currentTimeMillis();
	}
	void updateAccessTimeWithCommitDirty() {
		commitDirtyInd=!curAutoCommit;
		lastAccessTime=currentTimeMillis();
	}
    void setChangedInd(short pos,boolean changed){
		if(!changedInds[pos] && changed)//false ->true      + 1
		   changedCount++;
		else if(changedInds[pos] && !changed)//true-->false  -1
		   changedCount--;
		changedInds[pos]=changed;
		updateAccessTime();
    }

    boolean isSupportSchema() {
		return pool.isSupportSchema();
	}
	boolean isSupportNetworkTimeout() {
		return  pool.isSupportNetworkTimeout();
	}
	//reset connection on return to pool
	private boolean resetRawConnOnReturn() {
		if (!curAutoCommit&&commitDirtyInd){//Roll back when commit dirty
			try {
				rawConn.rollback();
				updateAccessTime();
				commitDirtyInd=false;
			} catch (Throwable e) {
				log.error("Failed to rollback on return to pool", e);
				return false;
			}
		}

		//reset begin
		if (changedCount > 0) {
			if (changedInds[0]) {//reset autoCommit
				try {
					rawConn.setAutoCommit(pConfig.isDefaultAutoCommit());
					curAutoCommit = pConfig.isDefaultAutoCommit();
					updateAccessTime();
					changedInds[0] = false;
				} catch (Throwable e) {
					log.error("Failed to reset autoCommit to:{}", pConfig.isDefaultAutoCommit(), e);
					return false;
				}
			}
			if (changedInds[1]) {
				try {
					rawConn.setTransactionIsolation(pConfig.getDefaultTransactionIsolationCode());
					updateAccessTime();
					changedInds[1] = false;
				} catch (Throwable e) {
					log.error("Failed to reset transactionIsolation to:{}", pConfig.getDefaultTransactionIsolation(), e);
					return false;
				}
			}

			if (changedInds[2]) {//reset readonly
				try {
					rawConn.setReadOnly(pConfig.isDefaultReadOnly());
					updateAccessTime();
					changedInds[2] = false;
				} catch (Throwable e) {
					log.error("Failed to reset readOnly to:{}", pConfig.isDefaultReadOnly(), e);
					return false;
				}
			}

			if (changedInds[3]) {//reset catalog
				try {
					rawConn.setCatalog(pConfig.getDefaultCatalog());
					updateAccessTime();
					changedInds[3] = false;
				} catch (Throwable e) {
					log.error("Failed to reset catalog to:{}", pConfig.getDefaultCatalog(), e);
					return false;
				}
			}
			//for JDK1.7 begin
			if (changedInds[4] && isSupportSchema()) {//reset shema
				try {
					rawConn.setSchema(pConfig.getDefaultSchema());
					updateAccessTime();
					changedInds[4] = false;
				} catch (Throwable e) {
					log.error("Failed to reset schema to:{}", pConfig.getDefaultSchema(), e);
					return false;
				}
			}

			if (changedInds[5] && isSupportNetworkTimeout()) {//reset networkTimeout
				try {
					rawConn.setNetworkTimeout(pool.getNetworkTimeoutExecutor(), pool.getNetworkTimeout());
					updateAccessTime();
					changedInds[5] = false;
				} catch (Throwable e) {
					log.error("Failed to reset networkTimeout to:{}", pool.getNetworkTimeout(), e);
					return false;
				}
			}
			//for JDK1.7 end
			changedCount = 0;
		}//reset end

		try {//clear warnings
			rawConn.clearWarnings();
			return true;
		} catch (Throwable e) {
			log.error("Failed to clear warnings",e);
			return false;
		}
	}
}
