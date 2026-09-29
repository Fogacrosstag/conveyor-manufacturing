import unittest,sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'scripts'))
from migrate import digest
class ReconciliationTest(unittest.TestCase):
    def test_key_order_and_integer_float_normalization(self):
        self.assertEqual(digest({'a':1,'b':[{'x':2.0}]}),digest({'b':[{'x':2}],'a':1.0}))
    def test_changed_quantity_is_not_equal(self):
        self.assertNotEqual(digest({'stock':1}),digest({'stock':2}))
    def test_boolean_is_not_numeric(self):
        self.assertNotEqual(digest({'active':True}),digest({'active':1}))
    def test_array_order_is_preserved(self):
        self.assertNotEqual(digest([1,2]),digest([2,1]))
if __name__=='__main__':unittest.main()
