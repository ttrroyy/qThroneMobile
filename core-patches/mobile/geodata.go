package mobile

import "qThroneCore/internal/providergeo"

func ConvertGeoRuleSet(input, output, kind, selector string) error {
	return providergeo.Convert(input, output, kind, selector)
}

func ValidateGeoFile(path string) error {
	return providergeo.Validate(path)
}
