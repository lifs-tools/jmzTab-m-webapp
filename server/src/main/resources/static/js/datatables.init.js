/* 
 * Copyright 2018 Leibniz Institut für Analytische Wissenschaften - ISAS e.V..
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

$(document).ready(
        function () {
          $('#validate').attr('disabled', true);
          $('#file').change(
                  function () {
                    if ($(this).val()) {
                      $('#validate').removeAttr('disabled');
                    } else {
                      $('#validate').attr('disabled', true);
                    }
                  });
        });
$('#validationResultTable').DataTable(
  {
    dom: 'Bfrtip',
    buttons: [
      {
        extend: 'copy',
        text: '<u>C</u>opy',
        key: {
          key: 'c',
          ctrlKey: true
        }
      },
      {
        extend: 'csv',
        text: 'TSV',
        filename: '*-validation',
        //className: 'btn btn-success',
        fieldSeparator: '\t',
        extension: '.tsv',
        exportOptions: {
          modifier: {
            search: 'none'
          }
        }
      }

    ]
  }
);
$('#evidenceDataRowsTable').DataTable(
  {
    dom: 'frtip'
  }
);
$('#featureDataRowsTable').DataTable(
  {
    dom: 'frtip'
  }
);
$('#metaDataRowsTable').DataTable(
  {
    dom: 'frtip'
  }
);
$('#peptidesDataRowsTable').DataTable(
  {
    dom: 'frtip'
  }
);
$('#proteinsDataRowsTable').DataTable(
  {
    dom: 'frtip'
  }
);
$('#psmsDataRowsTable').DataTable(
  {
    dom: 'frtip'
  }
);
$('#summaryDataRowsTable').DataTable(
  {
    dom: 'frtip'
  }
);