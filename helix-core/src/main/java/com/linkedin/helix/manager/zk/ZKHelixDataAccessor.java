package com.linkedin.helix.manager.zk;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;

import com.linkedin.helix.BaseDataAccessor;
import com.linkedin.helix.HelixDataAccessor;
import com.linkedin.helix.HelixProperty;
import com.linkedin.helix.PropertyKey;
import com.linkedin.helix.PropertyKey.Builder;
import com.linkedin.helix.PropertyPathConfig;
import com.linkedin.helix.PropertyType;
import com.linkedin.helix.ZNRecord;

public class ZKHelixDataAccessor implements HelixDataAccessor
{
  private static Logger LOG = Logger.getLogger(ZKHelixDataAccessor.class);
  private final BaseDataAccessor _baseDataAccessor;
  private final String _clusterName;
  private final Builder _propertyKeyBuilder;

  public ZKHelixDataAccessor(String clusterName,
      BaseDataAccessor baseDataAccessor)
  {
    _clusterName = clusterName;
    _baseDataAccessor = baseDataAccessor;
    _propertyKeyBuilder = new PropertyKey.Builder(_clusterName);
  }

  @Override
  public <T extends HelixProperty> boolean createProperty(PropertyKey key,
      T value)
  {
    PropertyType type = key.getType();
    String path = PropertyPathConfig.getPath(type, _clusterName,
        key.getParams());
    int options = constructOptions(type);
    return _baseDataAccessor.create(path, value.getRecord(), options);
  }

  @Override
  public <T extends HelixProperty> boolean setProperty(PropertyKey key, T value)
  {
    PropertyType type = key.getType();
    String path = PropertyPathConfig.getPath(type, _clusterName,
        key.getParams());
    int options = constructOptions(type);
    return _baseDataAccessor.set(path, value.getRecord(), options);
  }

  @Override
  public <T extends HelixProperty> boolean updateProperty(PropertyKey key,
      T value)
  {
    PropertyType type = key.getType();
    String path = PropertyPathConfig.getPath(type, _clusterName,
        key.getParams());
    int options = constructOptions(type);
    return _baseDataAccessor.update(path, value.getRecord(), options);
  }

  @Override
  public <T extends HelixProperty> T getProperty(PropertyKey key)
  {
    PropertyType type = key.getType();
    String path = PropertyPathConfig.getPath(type, _clusterName,
        key.getParams());
    int options = constructOptions(type);
    ZNRecord record = _baseDataAccessor.get(path, null, options);
    return (T) createPropertyObject(key.getTypeClass(), record);
  }

  @Override
  public boolean removeProperty(PropertyKey key)
  {
    PropertyType type = key.getType();
    String path = PropertyPathConfig.getPath(type, _clusterName,
        key.getParams());
    return _baseDataAccessor.remove(path);
  }

  @Override
  public List<String> getChildNames(PropertyKey key)
  {
    PropertyType type = key.getType();
    String parentPath = PropertyPathConfig.getPath(type, _clusterName,
        key.getParams());
    int options = constructOptions(type);
    return _baseDataAccessor.getChildNames(parentPath, options);
  }

  @Override
  public List<HelixProperty> getChildValues(PropertyKey key)
  {
    PropertyType type = key.getType();
    String parentPath = PropertyPathConfig.getPath(type, _clusterName,
        key.getParams());
    int options = constructOptions(type);
    List<ZNRecord> children = _baseDataAccessor
        .getChildren(parentPath, options);
    List<HelixProperty> childValues = new ArrayList<HelixProperty>();
    for (ZNRecord record : children)
    {
      childValues.add(createPropertyObject(key.getTypeClass(), record));
    }
    return childValues;
  }

  @Override
  public <T extends HelixProperty> Map<String, T> getChildValuesMap(
      PropertyKey key)
  {
    PropertyType type = key.getType();
    String parentPath = key.getPath();
    int options = constructOptions(type);
    List<ZNRecord> children = _baseDataAccessor
        .getChildren(parentPath, options);
    Map<String, T> childValuesMap = new HashMap<String, T>();
    for (ZNRecord record : children)
    {
      T t = createPropertyObject(key.getTypeClass(), record);
      childValuesMap.put(record.getId(), t);
    }
    return childValuesMap;
  }

  @Override
  public Builder keyBuilder()
  {
    return _propertyKeyBuilder;
  }

  private int constructOptions(PropertyType type)
  {
    int options = 0;
    if (type.isPersistent())
    {
      options = options | BaseDataAccessor.Option.PERSISTENT;
    } else
    {
      options = options | BaseDataAccessor.Option.EPHEMERAL;
    }
    return options;
  }

  private <T extends HelixProperty> T createPropertyObject(
      Class<? extends HelixProperty> clazz, ZNRecord record)
  {
    try
    {
      Constructor<? extends HelixProperty> constructor = clazz
          .getConstructor(ZNRecord.class);
      HelixProperty property = constructor.newInstance(record);
      return (T) property;
    } catch (Exception e)
    {
      LOG.error("Exception creating helix property instance:" + e.getMessage(),
          e);
    }
    return null;
  }

  @Override
  public <T extends HelixProperty> boolean[] createChildren(
      List<PropertyKey> keys, List<T> children)
  {
    // TODO: add validation
    int options = -1;
    List<String> paths = new ArrayList<String>();
    List<ZNRecord> records = new ArrayList<ZNRecord>();
    for (int i = 0; i < keys.size(); i++)
    {
      PropertyKey key = keys.get(i);
      PropertyType type = key.getType();
      String path = key.getPath();
      paths.add(path);
      HelixProperty value = children.get(i);
      records.add(value.getRecord());
      options = constructOptions(type);
    }
    return _baseDataAccessor.createChildren(paths, records, options);
  }

  @Override
  public <T extends HelixProperty> boolean[] setChildren(
      List<PropertyKey> keys, List<T> children)
  {
    int options = -1;
    List<String> paths = new ArrayList<String>();
    List<ZNRecord> records = new ArrayList<ZNRecord>();
    for (int i = 0; i < keys.size(); i++)
    {
      PropertyKey key = keys.get(i);
      PropertyType type = key.getType();
      String path = key.getPath();
      paths.add(path);
      HelixProperty value = children.get(i);
      records.add(value.getRecord());
      options = constructOptions(type);
    }
    return _baseDataAccessor.setChildren(paths, records, options);

  }

  @Override
  public BaseDataAccessor getBaseDataAccessor()
  {
    return _baseDataAccessor;
  }

}
