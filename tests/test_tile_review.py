from pathlib import Path
import runpy
import unittest

tile_review = runpy.run_path(str(Path(__file__).resolve().parents[1] / 'scripts/tile-review'))['tile_review']


class TileReviewTest(unittest.TestCase):
    def review(self, old, new, styles=('tactical',), pinned_styles=('tactical',)):
        published = {'https://t/tactical/old/index.json': old, 'https://t/tactical/new/index.json': new,
                     'https://t/standard/new/index.json': new}
        return tile_review(dict(base='https://t', version='new', styles=list(styles)),
                           dict(base='https://t', version='old', styles=list(pinned_styles)), fetch=published.get)

    def test_the_pinned_version_needs_no_review_or_fetches(self):
        pin = dict(base='https://t', version='same', styles=['tactical'])
        self.assertEqual(tile_review(pin, pin, fetch=None), dict(review=False, styles={}))

    def test_more_than_a_tenth_changed_needs_review_with_counts(self):
        old = {str(i): 'a' for i in range(20)}
        small = {**old, '0': 'b', '1': 'b'}
        self.assertFalse(self.review(old, small)['review'])
        large = {**small, '2': 'b', '20': 'c'}
        result = self.review(old, large)
        self.assertTrue(result['review'])
        self.assertEqual(result['styles']['tactical'], dict(tiles=21, changed=3, added=1, removed=0))

    def test_a_new_style_needs_review(self):
        result = self.review({'0': 'a'}, {'0': 'a'}, styles=('tactical', 'standard'))
        self.assertTrue(result['review'])
        self.assertEqual(result['styles']['standard'], dict(tiles=1, new=True))


if __name__ == '__main__':
    unittest.main()
