---
title: Fedora Export
identifier: intranda_export_fedora
published: true
description: Goobi plugin for exporting Goobi processes to a Fedora repository
---
## Introduction
This documentation describes the installation, configuration and use of the Fedora Export Plugin in Goobi workflow.


## Installation
The plugin must be installed in the following folder:

```bash
/opt/digiverso/goobi/plugins/export/plugin_intranda_export_fedora*.jar
```

There is also a configuration file, which must be located at the following location:

```bash
/opt/digiverso/goobi/config/intranda_export_fedora.xml
```


## Overview and functionality
An export step must be configured:

* Export DMS
* Automatic task
* Plugin for step: FedoraExport

When the step is executed, the Goobi process is exported (in the same way as it is exported to the file system) to the configured Fedora Repository, taking into account the configuration (see above).

The process data can then be retrieved from the repository using the following URL pattern:

```bash
http(s)://<Fedora REST endpoint>/records/&lt;CatalogIdDigital>/
```


## Examples of URLs after successful ingest to Fedora


### URL of the data record

[http://localhost:8888/fedora/rest/records/PPN123456789/](http://localhost:8888/fedora/rest/records/PPN123456789/)


### URL for the METS file

[http://localhost:8888/fedora/rest/records/PPN123456789/PPN123456789.xml](http://localhost:8888/fedora/rest/records/PPN123456789/PPN123456789.xml)


### URL for the master images

[http://localhost:8888/fedora/rest/records/PPN123456789/master/master_00000001.tif](http://localhost:8888/fedora/rest/records/PPN123456789/master/master_00000001.tif) [http://localhost:8888/fedora/rest/records/PPN123456789/master/master_00000002.tif](http://localhost:8888/fedora/rest/records/PPN123456789/master/master_00000002.tif) [http://localhost:8888/fedora/rest/records/PPN123456789/master/master_00000003.tif](http://localhost:8888/fedora/rest/records/PPN123456789/master/master_00000003.tif)


### URL for the derivatives of the images

[http://localhost:8888/fedora/rest/records/PPN123456789/media/00000001.tif](http://localhost:8888/fedora/rest/records/PPN123456789/media/00000001.tif) [http://localhost:8888/fedora/rest/records/PPN123456789/media/00000002.tif](http://localhost:8888/fedora/rest/records/PPN123456789/media/00000002.tif) [http://localhost:8888/fedora/rest/records/PPN123456789/media/00000003.tif](http://localhost:8888/fedora/rest/records/PPN123456789/media/00000003.tif)


## Configuration
The configuration is done via the configuration file `intranda_export_fedora.xml` and can be adapted during operation.

```xml
<config_plugin>

    <!-- fedoraUrl: REST endpoint of the target Fedora application. -->
    <fedoraUrl>http://localhost:8888/fedora/rest</fedoraUrl>

    <!-- useVersioning: If true, for each run of the export step, a new revision of the process will be created. Default is true. -->
    <useVersioning>true</useVersioning>

    <!-- ingestMasterImages: If true, master images of the Goobi process will be ingested into the container /master. Default is true. -->
    <ingestMasterImages>true</ingestMasterImages>

    <!-- ingestMediaImages: If true, derivate images of the Goobi process will be ingested into the container /media. Default is true. -->
    <ingestMediaImages>true</ingestMediaImages>

    <!-- ingestMetsFile: If true, a METS/MODS file will be generated and ingested. Default is true. -->
    <ingestMetsFile>true</ingestMetsFile>

    <!-- exportMetsFile: If true, the METS/MODS file will be exported into the given destination folder. Default is true. -->
    <exportMetsFile>true</exportMetsFile>

</config_plugin>
```

| Parameter | Description |
| :--- | :--- |
| `fedoraUrl` | REST Endpoint of the Fedora application |
| `useVersioning` | If `true`, the versioning of Fedora is used. In this case, each time the export step is executed, a new version of the process is created in the repository. The default value is `true`. |
| `ingestMasterImages` | If `true` is set, the master images of the operation are exported to the subcontainer `/master`. The default value is `true`. |
| `ingestMediaImages` | If `true`, the derivatives of the operation are exported to the `/media` subcontainer. The default value is `true`. |
| `ingestMetsFile` | If `true` is set, a METS/MODS file is created and exported to the container. Default value is `true`. |
| `exportMetsFile` | If `true` is set, a METS/MODS file is created and written to the usual export folder (e.g. `/hotfolder`). Default value is `true`. |