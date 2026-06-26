# File Transformation Feature

## Overview

The file transformation feature allows you to automatically process JSON files by tokenizing sensitive information. Files are monitored in source directories, transformed using the same RPS (RegData Protection Suite) engine used for REST payloads, and moved to target directories upon successful processing.

## Configuration

### Application Properties

Add these properties to your `application.properties` file to enable file transformation:

```properties
# Enable file transformation
proxy.file-transform.enabled=true

# Configuration file location
proxy.file-transform.config-file=./config/file_transform_config.json

# Scan interval in seconds (how often to check for new files)
proxy.file-transform.scan-interval-seconds=30
```

### File Transform Configuration

The `file_transform_config.json` file defines one or more file transformation configurations. Each configuration specifies:

#### Configuration Structure

```json
[
  {
    "name": "Configuration Name",
    "source-directory": "./data/source",
    "target-directory": "./data/target",
    "error-directory": "./data/error",
    "work-in-progress-suffix": ".processing",
    "file-pattern": ".*\\.json$",
    "preserve-directory-structure": true,
    "right-context": {
      "Location": "Onshore"
    },
    "processing-context": {
      "Action": "Protect",
      "Location": "Onshore"
    },
    "entity-transform-configs": [
      {
        "json-path": "$.firstName",
        "rps-class-name": "poc.Person",
        "rps-property-name": "Name"
      }
    ]
  }
]
```

#### Configuration Parameters

| Parameter | Type | Description | Default |
|-----------|------|-------------|---------|
| `name` | String | Descriptive name for this configuration | Required |
| `source-directory` | String | Directory to monitor for files to process | Required |
| `target-directory` | String | Directory where successfully processed files are moved | Required |
| `error-directory` | String | Directory where failed files are moved | Required |
| `work-in-progress-suffix` | String | Suffix added to files during processing | `.processing` |
| `file-pattern` | String | Regular expression to match file names | `.*\\.json$` |
| `preserve-directory-structure` | Boolean | Maintain subdirectory structure in target/error directories | `true` |
| `right-context` | Object | RPS right context evidences (key-value pairs) | `{}` |
| `processing-context` | Object | RPS processing context evidences (key-value pairs) | `{}` |
| `entity-transform-configs` | Array | List of JSON path transformation rules | `[]` |

#### Entity Transform Configs

Each entity transform configuration specifies which JSON fields to transform:

```json
{
  "json-path": "$.firstName",
  "rps-class-name": "poc.Person",
  "rps-property-name": "Name"
}
```

- **json-path**: JSONPath expression to locate the value(s) to transform
  - Use `$.fieldName` for root-level fields
  - Use `$.[*].fieldName` for arrays
  - Use `$.parent.child` for nested fields
- **rps-class-name**: RPS class name for tokenization
- **rps-property-name**: RPS property name for tokenization

## How It Works

### File Processing Workflow

1. **Scanning**: The service scans source directories at regular intervals (configured by `scan-interval-seconds`)

2. **Selection**: Files matching the `file-pattern` regex are selected for processing

3. **Work in Progress**: The file is renamed with the `.processing` suffix (or custom suffix) to prevent duplicate processing

4. **Transformation**: 
   - File content is read
   - JSON is parsed
   - Fields matching `entity-transform-configs` are extracted
   - RPS engine tokenizes/detokenizes the values
   - Transformed values are written back to JSON

5. **Success**: 
   - Transformed file is written to `target-directory`
   - Original (work-in-progress) file is deleted
   - Directory structure is preserved if configured

6. **Error**:
   - File is moved to `error-directory`
   - Original content is preserved for debugging
   - Error is logged

### Directory Structure Preservation

When `preserve-directory-structure` is `true`:

```
source/
  ├── folder1/
  │   └── file1.json
  └── folder2/
      └── file2.json

→ Becomes:

target/
  ├── folder1/
  │   └── file1.json
  └── folder2/
      └── file2.json
```

When `false`, all files are placed directly in the target directory root.

## Examples

### Example 1: Protect Person Data

```json
{
  "name": "Person Data Protection",
  "source-directory": "./data/source",
  "target-directory": "./data/target",
  "error-directory": "./data/error",
  "processing-context": {
    "Action": "Protect"
  },
  "entity-transform-configs": [
    {
      "json-path": "$.firstName",
      "rps-class-name": "poc.Person",
      "rps-property-name": "Name"
    },
    {
      "json-path": "$.lastName",
      "rps-class-name": "poc.Person",
      "rps-property-name": "Name"
    },
    {
      "json-path": "$.birthDate",
      "rps-class-name": "poc.Person",
      "rps-property-name": "BirthDate"
    }
  ]
}
```

**Input file** (`source/person.json`):
```json
{
  "firstName": "John",
  "lastName": "Doe",
  "birthDate": "1990-01-15"
}
```

**Output file** (`target/person.json`):
```json
{
  "firstName": "TOKEN_ABC123",
  "lastName": "TOKEN_XYZ789",
  "birthDate": "TOKEN_DATE456"
}
```

### Example 2: Unprotect Person Data

```json
{
  "name": "Person Data Unprotection",
  "source-directory": "./data/unprotect-source",
  "target-directory": "./data/unprotect-target",
  "error-directory": "./data/unprotect-error",
  "processing-context": {
    "Action": "Unprotect"
  },
  "entity-transform-configs": [
    {
      "json-path": "$.firstName",
      "rps-class-name": "poc.Person",
      "rps-property-name": "Name"
    }
  ]
}
```

### Example 3: Process Arrays

```json
{
  "name": "Array Processing",
  "source-directory": "./data/array-source",
  "target-directory": "./data/array-target",
  "error-directory": "./data/array-error",
  "entity-transform-configs": [
    {
      "json-path": "$.[*].firstName",
      "rps-class-name": "poc.Person",
      "rps-property-name": "Name"
    }
  ]
}
```

**Input file**:
```json
[
  { "firstName": "John", "age": 30 },
  { "firstName": "Jane", "age": 25 }
]
```

**Output file**:
```json
[
  { "firstName": "TOKEN_ABC123", "age": 30 },
  { "firstName": "TOKEN_XYZ789", "age": 25 }
]
```

## Monitoring and Logging

The service logs:
- Configuration loading at startup
- Directory creation
- File processing start/completion
- Errors with stack traces
- Files moved to error directory

Example log output:
```
INFO  File transformation is enabled
INFO  Loading file transform configuration from ./config/file_transform_config.json
INFO  Loaded 2 file transform configuration(s)
INFO  Created source directory: ./data/source
INFO  File transform scheduler started with interval: 30 seconds
INFO  Processing file: person.json
INFO  Successfully processed file: person.json -> ./data/target/person.json
```

## Best Practices

1. **Separate Configurations**: Use separate configurations for protect/unprotect operations
2. **Error Monitoring**: Regularly check error directories for failed files
3. **File Naming**: Use meaningful file names for easier troubleshooting
4. **Testing**: Test with sample files before production use
5. **Scan Interval**: Adjust based on file volume and processing requirements
6. **Directory Permissions**: Ensure the application has read/write access to all directories

## Troubleshooting

### Files Not Being Processed

- Check that `proxy.file-transform.enabled=true`
- Verify file pattern matches your files
- Check source directory path is correct
- Review logs for errors

### Files in Error Directory

- Check logs for specific error messages
- Verify JSON is well-formed
- Ensure RPS engine is properly configured
- Check network connectivity to RPS service

### Duplicate Processing

- Don't manually remove `.processing` files
- Ensure only one application instance is running
- Check file system supports atomic moves
