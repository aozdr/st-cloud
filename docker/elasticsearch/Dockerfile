FROM docker.elastic.co/elasticsearch/elasticsearch:8.12.0

# Install ingest-attachment plugin (Apache Tika for document parsing)
RUN bin/elasticsearch-plugin install --batch ingest-attachment

# Install IK Analysis plugin for Chinese text tokenization
RUN bin/elasticsearch-plugin install --batch https://release.infinilabs.com/analysis-ik/stable/elasticsearch-analysis-ik-8.12.0.zip
