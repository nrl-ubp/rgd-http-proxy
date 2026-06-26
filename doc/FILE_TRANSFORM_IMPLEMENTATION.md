# File Transformation Feature - Implementation Summary

## Overview
Added a file transformation feature to the RGD HTTP Proxy that allows batch processing of JSON files with the same tokenization capabilities used for REST API payloads.

## Files Created

### 1. Configuration Class
**Location:** `src/main/java/com/ubp/rgd/proxy/transform/config/FileTransformConfig.java`
- Defines the structure for file transformation configuration
- Supports multiple configurations for different source/target directories
- Reuses existing `EntityTransformConfig` for JSON path transformations
- Similar structure to `EndPointTransformConfig` but adapted for file processing
- **Package:** Placed in `transform.config` package for consistency with other transform configs

### 2. File Transform Service
**Location:** `src/main/java/com/ubp/rgd/proxy/services/FileTransformService.java`
- `@ApplicationScoped` CDI bean that runs automatically on application startup
- Scheduled background process that scans directories at configurable intervals
- Handles file transformation workflow:
  - Scans source directories for matching files
  - Renames files with `.processing` suffix during work
  - Transforms JSON content using RPS engine
  - Moves successful files to target directory
  - Moves failed files to error directory
- Preserves directory structure (optional)
- Thread-safe file processing
- **Package:** Placed in `services` package alongside `ProxyService`

### 3. Configuration File
**Location:** `config/file_transform_config.json`
- JSON array containing one or more transformation configurations
- Includes two example configurations:
  - Person Data Protection (tokenize/protect)
  - Person Data Unprotection (detokenize/unprotect)
- Each configuration defines:
  - Source, target, and error directories
  - File pattern matching (regex)
  - RPS context (right-context and processing-context)
  - JSON path transformations

### 4. Documentation
**Location:** `doc/FILE_TRANSFORM_FEATURE.md`
- Comprehensive user guide
- Configuration reference
- Usage examples
- Best practices and troubleshooting

### 5. Unit Tests
**Location:** `src/test/java/com/ubp/rgd/proxy/services/FileTransformConfigTest.java`
- Tests for configuration serialization/deserialization
- Validates JSON config structure
- Tests for sorting and mapping transform configs
- **Package:** Placed in `services` test package for consistency

## Configuration Properties

Add to `application.properties`:

```properties
# Enable/disable file transformation
proxy.file-transform.enabled=false

# Configuration file location
proxy.file-transform.config-file=./config/file_transform_config.json

# Scan interval in seconds
proxy.file-transform.scan-interval-seconds=30
```

## Key Features

### 1. Multiple Directory Configurations
- Support for multiple source/target/error directory sets
- Each configuration can have different transformation rules
- Useful for separate protect/unprotect workflows

### 2. Directory Structure Preservation
- Optional preservation of subdirectory structure
- Files maintain their relative path from source to target
- Useful for organized file systems

### 3. Work-in-Progress Tracking
- Files renamed during processing (`.processing` suffix)
- Prevents duplicate processing
- Atomic file operations for reliability

### 4. Error Handling
- Failed files moved to error directory
- Original content preserved for debugging
- Detailed error logging

### 5. Flexible File Matching
- Regex-based file pattern matching
- Default: `.*\.json$` (all JSON files)
- Can be customized per configuration

### 6. Transform Configuration Reuse
- Uses existing `EntityTransformConfig` class
- JSONPath-based field selection
- Same RPS engine as REST API transformations
- Consistent tokenization behavior

## Architecture Integration

### Service Lifecycle
1. **Startup**: `@PostConstruct` initializes the service
2. **Configuration Loading**: Reads `file_transform_config.json`
3. **Directory Creation**: Creates source/target/error directories if missing
4. **Scheduler Start**: Begins periodic scanning
5. **Processing Loop**: Continuously processes files
6. **Shutdown**: Graceful cleanup on application stop

### Dependencies
- **RPSClientEngineProvider**: Injected for RPS engine access
- **JsonPath**: For JSON manipulation (already in project)
- **Jackson**: For configuration parsing
- **ScheduledExecutorService**: For background scheduling

### Error Handling Strategy
- Non-fatal errors: Log and continue processing other files
- Fatal errors: Log and skip current configuration
- File errors: Move to error directory and continue
- Configuration errors: Log and disable service

## Usage Example

### 1. Enable the Feature
```properties
proxy.file-transform.enabled=true
proxy.file-transform.scan-interval-seconds=30
```

### 2. Configure Transformations
Edit `config/file_transform_config.json`:
```json
[
  {
    "name": "Protect Customer Data",
    "source-directory": "./data/incoming",
    "target-directory": "./data/protected",
    "error-directory": "./data/failed",
    "processing-context": {
      "Action": "Protect",
      "Location": "Onshore"
    },
    "entity-transform-configs": [
      {
        "json-path": "$.customerName",
        "rps-class-name": "poc.Person",
        "rps-property-name": "Name"
      }
    ]
  }
]
```

### 3. Place Files
Drop JSON files in `./data/incoming/`:
```json
{
  "customerName": "John Doe",
  "accountId": "12345"
}
```

### 4. Process Automatically
Files are processed every 30 seconds and moved to `./data/protected/`:
```json
{
  "customerName": "TOKEN_ABC123XYZ",
  "accountId": "12345"
}
```

## Testing

### Unit Tests
```bash
mvn test -Dtest=FileTransformConfigTest
```

### Integration Testing
1. Enable the service
2. Place test files in source directory
3. Monitor logs for processing messages
4. Verify files in target directory
5. Check error directory for failures

## Performance Considerations

### Scalability
- Scheduler uses single thread for scanning
- File processing is sequential within each configuration
- Can handle multiple configurations in parallel (future enhancement)
- Suitable for low-to-medium volume file processing

### Resource Usage
- Memory: Minimal (files processed one at a time)
- CPU: Depends on RPS engine and file size
- I/O: Sequential file operations
- Network: RPS engine calls (same as REST processing)

### Optimization Recommendations
- Adjust scan interval based on file volume
- Use separate configurations for high/low priority files
- Monitor error directory for recurring issues
- Consider separate instances for very high volume

## Security Considerations

1. **Directory Permissions**: Ensure proper file system permissions
2. **File Validation**: Only JSON files are processed
3. **Error Handling**: Failed files preserved for audit
4. **Logging**: All operations logged for traceability
5. **Atomicity**: Atomic file moves prevent partial processing

## Future Enhancements (Optional)

- [ ] Parallel file processing within configurations
- [ ] Batch processing for multiple files
- [ ] File archiving after successful processing
- [ ] Metrics and monitoring integration
- [ ] REST API for on-demand processing
- [ ] File format support beyond JSON (XML, CSV)
- [ ] Custom file naming patterns for output
- [ ] Conditional processing based on file content

## Compilation Status

✅ **Code compiles successfully with Maven**
```
[INFO] BUILD SUCCESS
[INFO] Total time:  1.895 s
[INFO] Compiling 42 source files
```

## Next Steps

1. Review the configuration file and adjust paths
2. Enable the feature in `application.properties`
3. Test with sample files
4. Monitor logs for proper operation
5. Adjust scan interval as needed
6. Set up monitoring for error directory

## Support

For questions or issues:
- Review logs in application output
- Check `doc/FILE_TRANSFORM_FEATURE.md` for detailed documentation
- Verify RPS engine connectivity
- Ensure proper file permissions
