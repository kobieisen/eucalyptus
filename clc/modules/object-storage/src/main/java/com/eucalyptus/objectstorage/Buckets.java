package com.eucalyptus.objectstorage;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.persistence.EntityTransaction;

import org.apache.log4j.Logger;

import com.eucalyptus.entities.Entities;
import com.eucalyptus.entities.TransactionException;
import com.eucalyptus.entities.Transactions;
import com.eucalyptus.objectstorage.entities.Bucket;
import com.eucalyptus.objectstorage.exceptions.s3.BucketAlreadyExistsException;
import com.eucalyptus.objectstorage.exceptions.s3.BucketAlreadyOwnedByYouException;
import com.eucalyptus.objectstorage.exceptions.s3.BucketNotEmptyException;
import com.eucalyptus.objectstorage.exceptions.s3.InternalErrorException;
import com.eucalyptus.objectstorage.exceptions.s3.InvalidBucketStateException;
import com.eucalyptus.objectstorage.exceptions.s3.S3Exception;
import com.eucalyptus.objectstorage.util.ObjectStorageProperties;
import com.eucalyptus.objectstorage.util.ObjectStorageProperties.VersioningStatus;

public enum Buckets implements BucketManager {
	INSTANCE;
	private static final Logger LOG = Logger.getLogger(Buckets.class);

	@Override
	public Bucket get(String bucketName, Callable<Boolean> resourceModifier) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public boolean exists(String bucketName, Callable<Boolean> resourceModifier) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public <T,R> T create(@Nonnull String bucketName, 
			@Nonnull String ownerCanonicalId,
			@Nonnull String ownerIamUserId,
			@Nonnull String acl, 
			@Nonnull String location,
			@Nullable ReversableOperation<T,R> resourceModifier) throws S3Exception, TransactionException {
		
		Bucket newBucket = new Bucket(bucketName);
		try {
			Bucket foundBucket = Transactions.find(newBucket);
			if(foundBucket != null) {
				if(foundBucket.getOwnerCanonicalId().equals(ownerCanonicalId)) {
					throw new BucketAlreadyOwnedByYouException(bucketName);
				} else {
					throw new BucketAlreadyExistsException(bucketName);
				}
			}
		} catch(TransactionException e) {
			//Lookup failed.
			LOG.error("Lookup for bucket " + bucketName + " failed during creation checks. Cannot proceed.",e);
			throw new InternalErrorException(bucketName);
		}
		
		newBucket.setOwnerCanonicalId(ownerCanonicalId);
		newBucket.setBucketSize(0L);
		newBucket.setHidden(false);
		newBucket.setAcl(acl);
		newBucket.setLocation(location);
		newBucket.setLoggingEnabled(false);
		newBucket.setOwnerIamUserId(ownerIamUserId);
		newBucket.setVersioning(ObjectStorageProperties.VersioningStatus.Disabled.toString());
		
		T result = null;		
		try {
			if(resourceModifier != null) { 
				result = resourceModifier.call();
			}
		} catch(Exception e) {
			LOG.error("Error creating bucket in backend",e);
			throw new InternalErrorException(bucketName);
		}
		
		try {
			Transactions.save(newBucket);			
		} catch(TransactionException ex) {
			//Rollback the bucket creation.
			LOG.error("Error persisting bucket record for bucket " + bucketName, ex);
			
			//Do backend cleanup here.
			if(resourceModifier != null) {
				try {
					R rollbackResult = resourceModifier.rollback(result);
				} catch(Exception e) {
					LOG.error("Backend rollback of operation failed",e);			
				}
			}
			throw ex;
		}
		return result;			
	}
	
	@Override
	public void delete(String bucketName, Callable<Boolean> resourceModifier) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void delete(Bucket bucketEntity, Callable<Boolean> resourceModifier) throws BucketNotEmptyException {
		try {
			//TODO: add emptiness check
			Transactions.delete(bucketEntity);
		} catch(TransactionException e) {
			
		}
	}

	@Override
	public void updateVersioningState(String bucketName,
			VersioningStatus newState, Callable<Boolean> resourceModifier) throws InvalidBucketStateException, TransactionException {
		
		EntityTransaction db = Entities.get(Bucket.class);
		try {
			Bucket searchBucket = new Bucket(bucketName);
			Bucket bucket = Entities.uniqueResult(searchBucket);
			if(VersioningStatus.Disabled.equals(newState) && !bucket.isVersioningDisabled()) {
				//Cannot set versioning disabled if not already disabled.
				throw new InvalidBucketStateException(bucketName);
			}
			bucket.setVersioning(newState.toString());			
			db.commit();
		} catch (TransactionException e) {
			LOG.error("Error updating versioning status for bucket " + bucketName + " due to DB transaction error", e);
			throw e;
		} finally {
			if(db != null && db.isActive()) {
				db.rollback();
			}
		}
	}

	@Override
	public List<Bucket> list(String ownerCanonicalId, boolean includeHidden, Callable<Boolean> resourceModifier) throws TransactionException {
		Bucket searchBucket = new Bucket();
		searchBucket.setOwnerCanonicalId(ownerCanonicalId);
		searchBucket.setHidden(includeHidden);
		List<Bucket> buckets = null;
		try {
			buckets = Transactions.findAll(searchBucket);
			return buckets;
		} catch (TransactionException e) {
			LOG.error("Error listing buckets for user " + ownerCanonicalId + " due to DB transaction error", e);
			throw e;
		}
	}
	
	@Override
	public List<Bucket> listByUser(String userIamId, boolean includeHidden, Callable<Boolean> resourceModifier) throws TransactionException {
		Bucket searchBucket = new Bucket();
		searchBucket.setHidden(includeHidden);
		searchBucket.setOwnerIamUserId(userIamId);
		List<Bucket> buckets = null;
		try {
			buckets = Transactions.findAll(searchBucket);
			return buckets;
		} catch (TransactionException e) {
			LOG.error("Error listing buckets for user " + userIamId + " due to DB transaction error", e);
			throw e;
		}
	}
	
	@Override
	public long countByUser(String userIamId, boolean includeHidden, Callable<Boolean> resourceModifier) throws ExecutionException {
		Bucket searchBucket = new Bucket();
		searchBucket.setHidden(includeHidden);
		searchBucket.setOwnerIamUserId(userIamId);
		EntityTransaction db = Entities.get(Bucket.class);
		try {
			return Entities.count(searchBucket);
		} catch (Exception e) {
			LOG.error("Error listing buckets for user " + userIamId + " due to DB transaction error", e);
			throw new ExecutionException(e);
		} finally {
			db.rollback();
		}
	}

}