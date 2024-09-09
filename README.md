# mavenPackageUploader

Simple program to upload maven packages to a remote package registry. Can be useful when migrating from one registry
to another. Benefits greatly from parallel operations.

### Tech Stack
Scala, cats, cats effect, fs2, sbt

### Usage
It's a console application that walks the supplied directory where packages are situated and calls
**mvn deploy:deploy-file** on every pom that was found. Since maven can sometime stuck, the records of uploaded and 
failed packages are kept. It requires 5 input parameters:
1) Url of a remote registry
2) Repository id
3) Path to root dir where your packages are situated.
4) Path to txt file with already uploaded files. These will be ignored and won't be uploaded. If this file doesn't
exist, it will be crated and paths to uploaded files will be added here
5) Path to txt file where the track of failed uploads will be kept

Make sure mvn, java 11 or later and sbt are installed to run this program