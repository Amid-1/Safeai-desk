#!/usr/bin/env python3
"""Изолированные регрессионные проверки контрольных скриптов."""
import importlib.util
import tempfile
import unittest
from pathlib import Path
from xml.etree import ElementTree as ET

HERE=Path(__file__).parent

def load(name,filename):
    spec=importlib.util.spec_from_file_location(name,HERE/filename)
    module=importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

inventory=load('safeai_inventory','check-backend-test-discovery.py')

class InventoryTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name)
        self.src=self.root/'backend/src/test/java/ru/safeai/gateway'
        self.reports=self.root/'backend/target/surefire-reports'
        self.reports.mkdir(parents=True)
        self.create('auth/integration/AuthPostgresConcurrencyIT.java','auth.integration','AuthPostgresConcurrencyIT')
        self.create('ratelimit/external/RedisClusterRateLimitIT.java','ratelimit.external','RedisClusterRateLimitIT')
        self.create('ratelimit/external/RedisSentinelFailoverIT.java','ratelimit.external','RedisSentinelFailoverIT')
    def create(self,rel,pkg,name):
        f=self.src/rel;f.parent.mkdir(parents=True,exist_ok=True)
        f.write_text(f'package ru.safeai.gateway.{pkg}; class {name} {{}}')
    def report(self,cls,skipped=False):
        suite=ET.Element('testsuite')
        case=ET.SubElement(suite,'testcase',classname=cls,name='testX')
        if skipped:ET.SubElement(case,'skipped')
        ET.ElementTree(suite).write(self.reports/'TEST-result.xml',encoding='utf-8')
    def test_local_it_executed(self):
        self.report('ru.safeai.gateway.auth.integration.AuthPostgresConcurrencyIT')
        result=inventory.verify(self.root)
        self.assertEqual(len(result['local_it']),1)
        self.assertEqual(len(result['external_it']),2)
    def test_local_it_missing_fails(self):
        self.report('ru.safeai.gateway.auth.integration.OtherTest')
        with self.assertRaisesRegex(ValueError,'Не запущены'):
            inventory.verify(self.root)
    def test_local_it_skipped_fails(self):
        self.report('ru.safeai.gateway.auth.integration.AuthPostgresConcurrencyIT',True)
        with self.assertRaisesRegex(ValueError,'Не запущены'):
            inventory.verify(self.root)
    def test_new_external_requires_policy_update(self):
        self.create('ratelimit/external/RedisNewTopologyIT.java','ratelimit.external','RedisNewTopologyIT')
        self.report('ru.safeai.gateway.auth.integration.AuthPostgresConcurrencyIT')
        with self.assertRaisesRegex(ValueError,'Изменился набор'):
            inventory.verify(self.root)

if __name__=='__main__':unittest.main()
